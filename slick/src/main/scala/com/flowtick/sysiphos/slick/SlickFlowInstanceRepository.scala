package com.flowtick.sysiphos.slick

import java.io.{ PrintWriter, StringWriter }

import com.flowtick.sysiphos.core.RepositoryContext
import com.flowtick.sysiphos.flow.FlowInstanceStatus.FlowInstanceStatus
import com.flowtick.sysiphos.flow._
import javax.sql.DataSource
import org.slf4j.{ Logger, LoggerFactory }
import slick.dbio.Effect.Read
import slick.jdbc.JdbcProfile

import scala.concurrent.{ ExecutionContext, Future }

final case class SlickFlowInstance(
  id: String,
  flowDefinitionId: String,
  created: Long,
  version: Long,
  updated: Option[Long],
  creator: String,
  status: String,
  startTime: Option[Long] = None,
  endTime: Option[Long] = None,
  error: Option[String] = None)

class SlickFlowInstanceRepository(
  dataSource: DataSource,
  idGenerator: IdGenerator = DefaultIdGenerator)(implicit val profile: JdbcProfile, executionContext: ExecutionContext)
  extends FlowInstanceRepository with SlickRepositoryBase {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import profile.api._

  val db: profile.backend.DatabaseDef = profile.backend.Database.forDataSource(dataSource, None, executor("flow-instance-repository"))

  class FlowInstances(tag: Tag) extends Table[SlickFlowInstance](tag, "_FLOW_INSTANCE") {
    def id = column[String]("_ID", O.PrimaryKey)
    def flowDefinitionId = column[String]("_FLOW_DEFINITION_ID")
    def created = column[Long]("_CREATED")
    def version = column[Long]("_VERSION")
    def updated = column[Option[Long]]("_UPDATED")
    def creator = column[String]("_CREATOR")
    def status = column[String]("_STATUS")
    def startTime = column[Option[Long]]("_START_TIME")
    def endTime = column[Option[Long]]("_END_TIME")
    def error = column[Option[String]]("_ERROR")

    def * = (id, flowDefinitionId, created, version, updated, creator, status, startTime, endTime, error) <> (SlickFlowInstance.tupled, SlickFlowInstance.unapply)
  }

  case class SysiphosFlowInstanceContext(
    id: String,
    flowInstanceId: String,
    key: String,
    value: String)

  class FlowInstanceContexts(tag: Tag) extends Table[SysiphosFlowInstanceContext](tag, "_FLOW_INSTANCE_CONTEXT") {
    def id = column[String]("_ID", O.PrimaryKey)
    def flowInstanceId = column[String]("_FLOW_INSTANCE_ID")
    def key = column[String]("_KEY")
    def value = column[String]("_VALUE")

    def * = (id, flowInstanceId, key, value) <> (SysiphosFlowInstanceContext.tupled, SysiphosFlowInstanceContext.unapply)
  }

  private val instanceTable = TableQuery[FlowInstances]
  private val contextTable = TableQuery[FlowInstanceContexts]

  private[slick] def getFlowInstances: Future[Seq[SlickFlowInstance]] = db.run(instanceTable.result)

  def toFlowInstanceDetails(instance: SlickFlowInstance): FlowInstanceDetails = {
    FlowInstanceDetails(
      instance.id,
      instance.flowDefinitionId,
      instance.created,
      instance.startTime,
      instance.endTime,
      FlowInstanceStatus.withName(instance.status),
      error = instance.error)
  }

  private def createQuery(query: FlowInstanceQuery) = {
    val filtered = instanceTable
      .filterOptional(query.flowDefinitionId)(flowDefinitionId => _.flowDefinitionId === flowDefinitionId)
      .filterOptional(query.instanceIds)(ids => _.id inSet ids.toSet)
      .filterOptional(query.status)(status => _.status.inSet(status.map(_.toString)))
      .filterOptional(query.createdGreaterThan)(createdGreaterThan => _.created >= createdGreaterThan)
      .filterOptional(query.createdSmallerThan)(createdSmallerThan => _.created <= createdSmallerThan)
      .sortBy(instance => (instance.created.desc, instance.id.desc))

    val withOffset = query.offset.map(filtered.drop).getOrElse(filtered)
    val withLimit = query.limit.map(withOffset.take).getOrElse(withOffset)

    withLimit.sortBy(instance => (instance.created.desc, instance.id))
  }

  override def getFlowInstances(query: FlowInstanceQuery)(implicit repositoryContext: RepositoryContext): Future[Seq[FlowInstanceDetails]] = {
    db.run(createQuery(query).result.map(_.map(toFlowInstanceDetails)))
  }

  override def createFlowInstance(
    flowDefinitionId: String,
    context: Seq[FlowInstanceContextValue],
    initialStatus: FlowInstanceStatus)(implicit repositoryContext: RepositoryContext): Future[FlowInstanceContext] = {
    val instanceToInsert = SlickFlowInstance(
      id = idGenerator.nextId,
      flowDefinitionId = flowDefinitionId,
      created = repositoryContext.epochSeconds,
      version = 0L,
      creator = repositoryContext.currentUser,
      updated = None,
      status = initialStatus.toString,
      startTime = None,
      endTime = None)

    val contextActions = context.map { contextValue =>
      contextTable += SysiphosFlowInstanceContext(idGenerator.nextId, instanceToInsert.id, contextValue.key, contextValue.value)
    }

    db.run(((instanceTable += instanceToInsert) >> DBIO.seq(contextActions: _*)).transactionally)
      .map(_ => FlowInstanceContext(toFlowInstanceDetails(instanceToInsert), context))
  }

  override def counts(
    flowDefinitionId: Option[Seq[String]],
    status: Option[Seq[FlowInstanceStatus.FlowInstanceStatus]],
    createdGreaterThan: Option[Long]): Future[Seq[InstanceCount]] = {
    val countQuery: DBIOAction[Seq[InstanceCount], NoStream, Read] = instanceTable
      .filterOptional(flowDefinitionId)(ids => _.flowDefinitionId inSet ids)
      .filterOptional(status)(statuses => _.status inSet statuses.map(_.toString))
      .filterOptional(createdGreaterThan)(created => _.created >= created)
      .groupBy(q => (q.flowDefinitionId, q.status))
      .map {
        case ((idValue, statusValue), groupedByIdAndStatus) => (idValue, statusValue, groupedByIdAndStatus.length)
      }.sortBy(_._2).result.map(_.map(InstanceCount.tupled))

    db.run(countQuery)
  }

  override def setStatus(flowInstanceId: String, status: FlowInstanceStatus.FlowInstanceStatus)(implicit repositoryContext: RepositoryContext): Future[Option[FlowInstanceDetails]] = {
    val columnsForUpdates = instanceTable.filter(_.id === flowInstanceId)
      .map { instance => instance.status }
      .update(status.toString)

    db.run(columnsForUpdates.transactionally).filter(_ == 1).flatMap { _ => findById(flowInstanceId) }
  }

  override def update(
    query: FlowInstanceQuery,
    status: FlowInstanceStatus.FlowInstanceStatus,
    error: Option[Throwable])(implicit repositoryContext: RepositoryContext): Future[Unit] = {
    val statusUpdate = createQuery(query)
      .map { instance => instance.status }
      .update(status.toString)

    def stackTrace(throwable: Throwable): String = {
      val sw = new StringWriter()
      throwable.printStackTrace(new PrintWriter(sw))
      sw.toString
    }

    val errorUpdate = error.map(throwable => createQuery(query)
      .map { instance => instance.error }
      .update(Some(stackTrace(throwable))))

    db.run(DBIO.seq(statusUpdate) >> DBIO.sequenceOption(errorUpdate).transactionally).map(_ => ())
  }

  override def setStartTime(flowInstanceId: String, startTime: Long)(implicit repositoryContext: RepositoryContext): Future[Option[FlowInstanceDetails]] = {
    val columnsForUpdates = instanceTable.filter(_.id === flowInstanceId)
      .map { instance => instance.startTime }
      .update(Some(startTime))

    db.run(columnsForUpdates.transactionally).filter(_ == 1).flatMap { _ => findById(flowInstanceId) }
  }

  override def setEndTime(flowInstanceId: String, endTime: Long)(implicit repositoryContext: RepositoryContext): Future[Option[FlowInstanceDetails]] = {
    val columnsForUpdates = instanceTable.filter(_.id === flowInstanceId)
      .map { instance => instance.endTime }
      .update(Some(endTime))

    db.run(columnsForUpdates.transactionally).filter(_ == 1).flatMap { _ => findById(flowInstanceId) }
  }

  override def findById(id: String)(implicit repositoryContext: RepositoryContext): Future[Option[FlowInstanceDetails]] = {
    val instanceWithId = instanceTable.filter(_.id === id).result.headOption

    db.run(instanceWithId).map {
      case Some(instance) => Some(toFlowInstanceDetails(instance))
      case _ => None
    }
  }

  def findContext(query: FlowInstanceQuery)(implicit repositoryContext: RepositoryContext): Future[Seq[FlowInstanceContext]] = {
    val filteredInstances = createQuery(query)

    val instancesWithContext = (for {
      (instance, context) <- filteredInstances joinLeft contextTable on (_.id === _.flowInstanceId)
    } yield (instance, context)).result

    db.run(instancesWithContext).flatMap(instances => {
      val groupedByInstance = instances.groupBy { case (instance, _) => instance }
      val instancesWithContextValues = instances.map {
        case (instance, _) =>
          val contextValues = groupedByInstance
            .getOrElse(instance, Seq.empty)
            .flatMap { case (_, contextValue) => contextValue }
            .map(contextValue => FlowInstanceContextValue(contextValue.key, contextValue.value))

          FlowInstanceContext(toFlowInstanceDetails(instance), contextValues)
      }.distinct

      Future.successful(instancesWithContextValues)
    })
  }

  def getContextValues(id: String)(implicit repositoryContext: RepositoryContext): Future[Seq[FlowInstanceContextValue]] = {
    val instanceWithContext = (for {
      (instance, context) <- instanceTable.filter(_.id === id) joinLeft contextTable on (_.id === _.flowInstanceId)
    } yield (instance, context)).result

    db.run(instanceWithContext)
      .map(result => result.flatMap(_._2).map(contextValue => FlowInstanceContextValue(contextValue.key, contextValue.value)))
  }

  override def insertOrUpdateContextValues(
    flowInstanceId: String,
    contextValues: Seq[FlowInstanceContextValue])(implicit repositoryContext: RepositoryContext): Future[Option[FlowInstanceDetails]] = {
    val insertOrUpdate = contextValues.map { contextValue =>
      DBIO.seq(
        contextTable
          .filter(_.flowInstanceId === flowInstanceId)
          .filter(_.key === contextValue.key)
          .delete,
        contextTable += SysiphosFlowInstanceContext(idGenerator.nextId, flowInstanceId, contextValue.key, contextValue.value))
    }

    db.run(DBIO.seq(insertOrUpdate: _*).transactionally).flatMap(_ => findById(flowInstanceId))
  }

  def contextValues: Future[Seq[SysiphosFlowInstanceContext]] = db.run(contextTable.result)

  override def deleteFlowInstance(flowInstanceId: String)(implicit repositoryContext: RepositoryContext): Future[String] = {
    val delete = DBIO.seq(
      contextTable.filter(_.flowInstanceId === flowInstanceId).delete,
      instanceTable.filter(_.id === flowInstanceId).delete).transactionally
    db.run(delete).flatMap(_ => Future(flowInstanceId))
  }
}
