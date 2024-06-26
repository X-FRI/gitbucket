package gitbucket.core.service

import java.util.Date
import gitbucket.core.controller.Context
import gitbucket.core.model.Account
import gitbucket.core.service.RepositoryService.RepositoryInfo
import gitbucket.core.util._
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.lib._
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.diff.{DiffEntry, DiffFormatter}
import java.io.ByteArrayInputStream
import org.eclipse.jgit.patch._
import org.eclipse.jgit.api.errors.PatchFormatException

import scala.jdk.CollectionConverters._
import scala.util.Using

object WikiService {

    /**
   * The model for wiki page.
   *
   * @param name the page name
   * @param content the page content
   * @param committer the last committer
   * @param time the last modified time
   * @param id the latest commit id
   */
    case class WikiPageInfo(
        name: String,
        content: String,
        committer: String,
        time: Date,
        id: String
    )

    /**
   * The model for wiki page history.
   *
   * @param name the page name
   * @param committer the committer the committer
   * @param message the commit message
   * @param date the commit date
   */
    case class WikiPageHistoryInfo(name: String, committer: String, message: String, date: Date)

    def wikiHttpUrl(repositoryInfo: RepositoryInfo)(implicit context: Context): String =
        RepositoryService.httpUrl(repositoryInfo.owner, repositoryInfo.name + ".wiki")

    def wikiSshUrl(repositoryInfo: RepositoryInfo)(implicit context: Context): Option[String] =
        RepositoryService.sshUrl(repositoryInfo.owner, repositoryInfo.name + ".wiki")

}

trait WikiService {
    import WikiService._

    def createWikiRepository(
        loginAccount: Account,
        owner: String,
        repository: String,
        defaultBranch: String
    ): Unit = LockUtil.lock(s"${owner}/${repository}/wiki") {
        val dir = Directory.getWikiRepositoryDir(owner, repository)
        if (!dir.exists) {
            JGitUtil.initRepository(dir, defaultBranch)
            saveWikiPage(
              owner,
              repository,
              "Home",
              "Home",
              s"Welcome to the ${repository} wiki!!",
              loginAccount,
              "Initial Commit",
              None
            )
        }
    }

    /**
   * Returns the wiki page.
   */
    def getWikiPage(
        owner: String,
        repository: String,
        pageName: String,
        branch: String
    ): Option[WikiPageInfo] = {
        Using.resource(Git.open(Directory.getWikiRepositoryDir(owner, repository))) { git =>
            if (!JGitUtil.isEmpty(git)) {
                val fileName = pageName + ".md"
                JGitUtil.getLatestCommitFromPath(git, fileName, branch).map { latestCommit =>
                    val content = JGitUtil
                        .getContentFromPath(git, latestCommit.getTree, fileName, true)
                    WikiPageInfo(
                      fileName,
                      StringUtil.convertFromByteArray(content.getOrElse(Array.empty)),
                      latestCommit.getAuthorIdent.getName,
                      latestCommit.getAuthorIdent.getWhen,
                      latestCommit.getName
                    )
                }
            } else None
        }
    }

    /**
   * Returns the list of wiki page names.
   */
    def getWikiPageList(owner: String, repository: String, branch: String): List[String] = {
        Using.resource(Git.open(Directory.getWikiRepositoryDir(owner, repository))) { git =>
            JGitUtil.getFileList(git, branch, ".").filter(_.name.endsWith(".md"))
                .filterNot(_.name.startsWith("_")).map(_.name.stripSuffix(".md")).sortBy(x => x)
        }
    }

    /**
   * Reverts specified changes.
   */
    def revertWikiPage(
        owner: String,
        repository: String,
        from: String,
        to: String,
        committer: Account,
        pageName: Option[String],
        branch: String
    ): Boolean = {

        case class RevertInfo(operation: String, filePath: String, source: String)

        try {
            LockUtil.lock(s"${owner}/${repository}/wiki") {
                Using.resource(Git.open(Directory.getWikiRepositoryDir(owner, repository))) { git =>
                    val reader = git.getRepository.newObjectReader
                    val oldTreeIter = new CanonicalTreeParser
                    oldTreeIter.reset(reader, git.getRepository.resolve(from + "^{tree}"))

                    val newTreeIter = new CanonicalTreeParser
                    newTreeIter.reset(reader, git.getRepository.resolve(to + "^{tree}"))

                    val diffs = git.diff.setNewTree(oldTreeIter).setOldTree(newTreeIter).call
                        .asScala.filter { diff =>
                            pageName match {
                                case Some(x) => diff.getNewPath == x + ".md"
                                case None    => true
                            }
                        }

                    val patch = Using.resource(new java.io.ByteArrayOutputStream()) { out =>
                        val formatter = new DiffFormatter(out)
                        formatter.setRepository(git.getRepository)
                        formatter.format(diffs.asJava)
                        new String(out.toByteArray, "UTF-8")
                    }

                    val p = new Patch()
                    p.parse(new ByteArrayInputStream(patch.getBytes("UTF-8")))
                    if (!p.getErrors.isEmpty) { throw new PatchFormatException(p.getErrors()) }
                    val revertInfo = p.getFiles.asScala.flatMap { fh =>
                        fh.getChangeType match {
                            case DiffEntry.ChangeType.MODIFY => {
                                val source = getWikiPage(
                                  owner,
                                  repository,
                                  fh.getNewPath.stripSuffix(".md"),
                                  branch
                                ).map(_.content).getOrElse("")
                                val applied = PatchUtil.apply(source, patch, fh)
                                if (applied != null) {
                                    Seq(RevertInfo("ADD", fh.getNewPath, applied))
                                } else Nil
                            }
                            case DiffEntry.ChangeType.ADD => {
                                val applied = PatchUtil.apply("", patch, fh)
                                if (applied != null) {
                                    Seq(RevertInfo("ADD", fh.getNewPath, applied))
                                } else Nil
                            }
                            case DiffEntry.ChangeType.DELETE => {
                                Seq(RevertInfo("DELETE", fh.getNewPath, ""))
                            }
                            case DiffEntry.ChangeType.RENAME => {
                                val applied = PatchUtil.apply("", patch, fh)
                                if (applied != null) {
                                    Seq(
                                      RevertInfo("DELETE", fh.getOldPath, ""),
                                      RevertInfo("ADD", fh.getNewPath, applied)
                                    )
                                } else { Seq(RevertInfo("DELETE", fh.getOldPath, "")) }
                            }
                            case _ => Nil
                        }
                    }

                    if (revertInfo.nonEmpty) {
                        val builder = DirCache.newInCore.builder()
                        val inserter = git.getRepository.newObjectInserter()
                        val headId = git.getRepository.resolve(Constants.HEAD + "^{commit}")

                        JGitUtil.processTree(git, headId) { (path, tree) =>
                            if (!revertInfo.exists(x => x.filePath == path)) {
                                builder.add(JGitUtil.createDirCacheEntry(
                                  path,
                                  tree.getEntryFileMode,
                                  tree.getEntryObjectId
                                ))
                            }
                        }

                        revertInfo.filter(_.operation == "ADD").foreach { x =>
                            builder.add(JGitUtil.createDirCacheEntry(
                              x.filePath,
                              FileMode.REGULAR_FILE,
                              inserter.insert(Constants.OBJ_BLOB, x.source.getBytes("UTF-8"))
                            ))
                        }
                        builder.finish()

                        JGitUtil.createNewCommit(
                          git,
                          inserter,
                          headId,
                          builder.getDirCache.writeTree(inserter),
                          Constants.HEAD,
                          committer.fullName,
                          committer.mailAddress,
                          pageName match {
                              case Some(x) => s"Revert ${from} ... ${to} on ${x}"
                              case None    => s"Revert ${from} ... ${to}"
                          }
                        )
                    }
                }
            }
            true
        } catch {
            case e: Exception => {
                e.printStackTrace()
                false
            }
        }
    }

    /**
   * Save the wiki page and return the commit id.
   */
    def saveWikiPage(
        owner: String,
        repository: String,
        currentPageName: String,
        newPageName: String,
        content: String,
        committer: Account,
        message: String,
        currentId: Option[String]
    ): Option[String] = {
        LockUtil.lock(s"${owner}/${repository}/wiki") {
            Using.resource(Git.open(Directory.getWikiRepositoryDir(owner, repository))) { git =>
                val builder = DirCache.newInCore.builder()
                val inserter = git.getRepository.newObjectInserter()
                val headId = git.getRepository.resolve(Constants.HEAD + "^{commit}")
                var created = true
                var updated = false
                var removed = false

                if (headId != null) {
                    JGitUtil.processTree(git, headId) { (path, tree) =>
                        if (path == currentPageName + ".md" && currentPageName != newPageName) {
                            removed = true
                        } else if (path != newPageName + ".md") {
                            builder.add(JGitUtil.createDirCacheEntry(
                              path,
                              tree.getEntryFileMode,
                              tree.getEntryObjectId
                            ))
                        } else {
                            created = false
                            updated = JGitUtil.getContentFromId(git, tree.getEntryObjectId, true)
                                .exists(new String(_, "UTF-8") != content)
                        }
                    }
                }

                if (created || updated || removed) {
                    builder.add(JGitUtil.createDirCacheEntry(
                      newPageName + ".md",
                      FileMode.REGULAR_FILE,
                      inserter.insert(Constants.OBJ_BLOB, content.getBytes("UTF-8"))
                    ))
                    builder.finish()
                    val newHeadId = JGitUtil.createNewCommit(
                      git,
                      inserter,
                      headId,
                      builder.getDirCache.writeTree(inserter),
                      Constants.HEAD,
                      committer.fullName,
                      committer.mailAddress,
                      if (message.trim.isEmpty) {
                          if (removed) { s"Rename ${currentPageName} to ${newPageName}" }
                          else if (created) { s"Created ${newPageName}" }
                          else { s"Updated ${newPageName}" }
                      } else { message }
                    )

                    Some(newHeadId.getName)
                } else None
            }
        }
    }

    /**
   * Delete the wiki page.
   */
    def deleteWikiPage(
        owner: String,
        repository: String,
        pageName: String,
        committer: String,
        mailAddress: String,
        message: String
    ): Unit = {
        LockUtil.lock(s"${owner}/${repository}/wiki") {
            Using.resource(Git.open(Directory.getWikiRepositoryDir(owner, repository))) { git =>
                val builder = DirCache.newInCore.builder()
                val inserter = git.getRepository.newObjectInserter()
                val headId = git.getRepository.resolve(Constants.HEAD + "^{commit}")
                var removed = false

                JGitUtil.processTree(git, headId) { (path, tree) =>
                    if (path != pageName + ".md") {
                        builder.add(JGitUtil.createDirCacheEntry(
                          path,
                          tree.getEntryFileMode,
                          tree.getEntryObjectId
                        ))
                    } else { removed = true }
                }
                if (removed) {
                    builder.finish()
                    JGitUtil.createNewCommit(
                      git,
                      inserter,
                      headId,
                      builder.getDirCache.writeTree(inserter),
                      Constants.HEAD,
                      committer,
                      mailAddress,
                      message
                    )
                }
            }
        }
    }

}
