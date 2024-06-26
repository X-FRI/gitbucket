package gitbucket.core.util

import gitbucket.core.api.JsonFormat
import gitbucket.core.controller.Context
import gitbucket.core.servlet.Database

import java.util.regex.Pattern.quote

import javax.servlet.http.{HttpSession, HttpServletRequest}

import scala.util.matching.Regex
import scala.util.control.Exception._

import slick.jdbc.JdbcBackend

/**
 * Provides some usable implicit conversions.
 */
object Implicits {

    // Convert to slick session.
    implicit def request2Session(implicit request: HttpServletRequest): JdbcBackend#Session =
        Database.getSession(request)

    implicit def context2ApiJsonFormatContext(implicit context: Context): JsonFormat.Context =
        JsonFormat.Context(context.baseUrl, context.settings.sshUrl)

    implicit class RichSeq[A](private val seq: Seq[A]) extends AnyVal {

        def splitWith(condition: (A, A) => Boolean): Seq[Seq[A]] = split(seq)(condition)

        @scala.annotation.tailrec
        private def split[A](list: Seq[A], result: Seq[Seq[A]] = Nil)(
            condition: (A, A) => Boolean
        ): Seq[Seq[A]] = list match {
            case x :: xs => {
                xs.span(condition(x, _)) match {
                    case (matched, remained) => split(remained, result :+ (x :: matched))(condition)
                }
            }
            case Nil => result
        }
    }

    implicit class RichString(private val value: String) extends AnyVal {
        def replaceBy(regex: Regex)(replace: Regex.MatchData => Option[String]): String = {
            val sb = new StringBuilder()
            var i = 0
            regex.findAllIn(value).matchData.foreach { m =>
                sb.append(value.substring(i, m.start))
                i = m.end
                replace(m) match {
                    case Some(s) => sb.append(s)
                    case None    => sb.append(m.matched)
                }
            }
            if (i < value.length) { sb.append(value.substring(i)) }
            sb.toString
        }

        def toIntOpt: Option[Int] = catching(classOf[NumberFormatException]) opt {
            Integer.parseInt(value)
        }
    }

    implicit class RichRequest(private val request: HttpServletRequest) extends AnyVal {

        def paths: Array[String] = (request.getRequestURI
            .substring(request.getContextPath.length + 1) match {
            case path if path.startsWith("api/v3/repos/") =>
                path.substring(13 /* "/api/v3/repos".length */ )
            case path if path.startsWith("api/v3/orgs/") =>
                path.substring(12 /* "/api/v3/orgs".length */ )
            case path => path
        }).split("/")

        def hasQueryString: Boolean = request.getQueryString != null

        def hasAttribute(name: String): Boolean = request.getAttribute(name) != null

        def gitRepositoryPath: String = request.getRequestURI
            .replaceFirst("^" + quote(request.getContextPath) + "/git/", "/")

    }

    implicit class RichSession(private val session: HttpSession) extends AnyVal {
        def getAndRemove[T](key: String): Option[T] = {
            val value = session.getAttribute(key).asInstanceOf[T]
            if (value == null) { session.removeAttribute(key) }
            Option(value)
        }
    }

}
