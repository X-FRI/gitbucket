package gitbucket.core.model

trait MilestoneComponent extends TemplateComponent {
    self: Profile =>
    import profile.api._
    import self._

    lazy val Milestones = TableQuery[Milestones]

    class Milestones(tag: Tag) extends Table[Milestone](tag, "MILESTONE") with MilestoneTemplate {
        override val milestoneId = column[Int]("MILESTONE_ID", O AutoInc)
        val title = column[String]("TITLE")
        val description = column[Option[String]]("DESCRIPTION")
        val dueDate = column[Option[java.util.Date]]("DUE_DATE")
        val closedDate = column[Option[java.util.Date]]("CLOSED_DATE")
        def * = (userName, repositoryName, milestoneId, title, description, dueDate, closedDate)
            .mapTo[Milestone]

        def byPrimaryKey(owner: String, repository: String, milestoneId: Int) =
            byMilestone(owner, repository, milestoneId)
        def byPrimaryKey(
            userName: Rep[String],
            repositoryName: Rep[String],
            milestoneId: Rep[Int]
        ) = byMilestone(userName, repositoryName, milestoneId)
    }
}

case class Milestone(
    userName: String,
    repositoryName: String,
    milestoneId: Int = 0,
    title: String,
    description: Option[String],
    dueDate: Option[java.util.Date],
    closedDate: Option[java.util.Date]
)
