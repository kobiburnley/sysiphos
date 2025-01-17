package com.flowtick.sysiphos.slick

import java.util.UUID

import com.flowtick.sysiphos.core.RepositoryContext
import com.flowtick.sysiphos.scheduler.{ FlowScheduleDetails, FlowScheduleRepository, FlowScheduleStateStore }
import javax.sql.DataSource
import org.slf4j.{ Logger, LoggerFactory }
import slick.jdbc.JdbcProfile

import scala.concurrent.{ ExecutionContext, Future }

class SlickFlowScheduleRepository(dataSource: DataSource)(implicit val profile: JdbcProfile, executionContext: ExecutionContext)
  extends FlowScheduleRepository with FlowScheduleStateStore with SlickRepositoryBase {

  val log: Logger = LoggerFactory.getLogger(getClass)

  import profile.api._

  val db: profile.backend.DatabaseDef = profile.backend.Database.forDataSource(dataSource, None, executor("flow-schedule-repository"))

  class FlowSchedules(tag: Tag) extends Table[FlowScheduleDetails](tag, "_FLOW_SCHEDULE") {
    def id = column[String]("_ID", O.PrimaryKey)
    def creator = column[String]("_CREATOR")
    def created = column[Long]("_CREATED")
    def version = column[Long]("_VERSION")
    def updated = column[Option[Long]]("_UPDATED")
    def expression = column[Option[String]]("_EXPRESSION")
    def flowDefinitionId = column[String]("_FLOW_DEFINITION_ID")
    def flowTaskId = column[Option[String]]("_FLOW_TASK_ID")
    def nextDueDate = column[Option[Long]]("_NEXT_DUE_DATE")
    def enabled = column[Option[Boolean]]("_ENABLED")
    def backFill = column[Option[Boolean]]("_BACK_FILL")

    def * = (id, creator, created, version, updated, expression, flowDefinitionId, flowTaskId, nextDueDate, enabled, backFill) <> (FlowScheduleDetails.tupled, FlowScheduleDetails.unapply)
  }

  val flowSchedulesTable = TableQuery[FlowSchedules]

  def newId: String = UUID.randomUUID().toString

  override def createFlowSchedule(
    id: Option[String],
    expression: Option[String],
    flowDefinitionId: String,
    flowTaskId: Option[String],
    enabled: Option[Boolean],
    backFill: Option[Boolean])(implicit repositoryContext: RepositoryContext): Future[FlowScheduleDetails] = {
    val newSchedule = FlowScheduleDetails(
      id = id.getOrElse(newId),
      creator = repositoryContext.currentUser,
      created = repositoryContext.epochSeconds,
      version = 0L,
      updated = None,
      expression = expression,
      flowDefinitionId = flowDefinitionId,
      flowTaskId,
      None,
      enabled = enabled,
      backFill = backFill)

    db.run(flowSchedulesTable += newSchedule)
      .filter(_ > 0)
      .map(_ => newSchedule)
  }

  override def getFlowSchedules(enabled: Option[Boolean], flowId: Option[String])(implicit repositoryContext: RepositoryContext): Future[Seq[FlowScheduleDetails]] = {
    val filteredSchedules = flowSchedulesTable
      .filterOptional(enabled)(enabled => _.enabled === enabled)
      .filterOptional(flowId)(flowId => _.flowDefinitionId === flowId)
      .sortBy(schedule => (schedule.flowDefinitionId, schedule.flowTaskId))

    db.run(filteredSchedules.result)
  }

  def findById(id: String)(implicit repositoryContext: RepositoryContext): Future[Option[FlowScheduleDetails]] = {
    val filteredSchedules = flowSchedulesTable.filter(_.id === id).result.headOption

    db.run(filteredSchedules)
  }

  override def setDueDate(flowScheduleId: String, dueDate: Long)(implicit repositoryContext: RepositoryContext): Future[Unit] = {
    val dueDateUpdate = flowSchedulesTable
      .filter(_.id === flowScheduleId)
      .map(schedule => (schedule.nextDueDate, schedule.updated))
      .update((Some(dueDate), Some(repositoryContext.epochSeconds)))

    db.run(dueDateUpdate)
      .filter(_ == 1)
      .map(_ => ())
  }

  override def updateFlowSchedule(
    id: String,
    expression: Option[String],
    enabled: Option[Boolean],
    backFill: Option[Boolean])(implicit repositoryContext: RepositoryContext): Future[FlowScheduleDetails] = {
    db.run(flowSchedulesTable.filter(_.id === id).result.headOption).flatMap {
      case Some(existing) =>
        val newExpression = expression.orElse(existing.expression)
        val newEnabled = enabled.orElse(existing.enabled)
        val newBackFill = backFill.orElse(existing.backFill)

        val scheduleUpdate =
          flowSchedulesTable
            .filter(_.id === existing.id)
            .map(flow => (flow.expression, flow.enabled, flow.backFill, flow.nextDueDate, flow.version, flow.updated))
            .update((newExpression, newEnabled, newBackFill, existing.nextDueDate, existing.version + 1, Some(repositoryContext.epochSeconds)))

        db.run(scheduleUpdate)
          .filter(_ > 0)
          .flatMap(_ => findById(id))
          .flatMap {
            case Some(schedule) => Future.successful(schedule)
            case None => Future.failed(new IllegalArgumentException(s"unable to find $id"))
          }
      case None => Future.failed(new IllegalArgumentException(s"could not find schedule with id $id"))
    }
  }

  override def delete(id: String)(implicit repositoryContext: RepositoryContext): Future[Unit] = {
    db.run(flowSchedulesTable.filter(_.id === id).delete).flatMap {
      case 1 => Future.successful(())
      case other => Future.failed(new IllegalStateException(s"updated $other rows during delete"))
    }
  }
}
