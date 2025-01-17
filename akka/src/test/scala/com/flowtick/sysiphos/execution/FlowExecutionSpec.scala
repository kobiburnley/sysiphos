package com.flowtick.sysiphos.execution

import com.flowtick.sysiphos.core.{ DefaultRepositoryContext, RepositoryContext }
import com.flowtick.sysiphos.flow.FlowDefinition.SysiphosDefinition
import com.flowtick.sysiphos.flow._
import com.flowtick.sysiphos.scheduler._
import com.flowtick.sysiphos.task.CommandLineTask
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatest.{ FlatSpec, Matchers }

import scala.concurrent.{ ExecutionContext, Future }

class FlowExecutionSpec extends FlatSpec with FlowExecution with Matchers with MockFactory
  with ScalaFutures with IntegrationPatience {

  override val flowInstanceRepository: FlowInstanceRepository = mock[FlowInstanceRepository]
  override val flowDefinitionRepository: FlowDefinitionRepository = mock[FlowDefinitionRepository]
  override val flowScheduleRepository: FlowScheduleRepository = mock[FlowScheduleRepository]
  override val flowScheduler: FlowScheduler = mock[FlowScheduler]
  override val flowTaskInstanceRepository: FlowTaskInstanceRepository = mock[FlowTaskInstanceRepository]
  override val flowScheduleStateStore: FlowScheduleStateStore = mock[FlowScheduleStateStore]

  override implicit val repositoryContext: RepositoryContext = new DefaultRepositoryContext("test-user")

  override implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global

  val testSchedule = FlowScheduleDetails(
    "test-schedule",
    "test",
    0,
    0,
    None,
    None,
    "flow-definition",
    None,
    nextDueDate = Some(0),
    enabled = Some(true),
    backFill = Some(false))

  val newInstance = FlowInstanceContext(FlowInstanceDetails(
    status = FlowInstanceStatus.Scheduled,
    id = "1",
    flowDefinitionId = testSchedule.flowDefinitionId,
    creationTime = 2L,
    startTime = None,
    endTime = None), Seq.empty)

  "Akka flow executor" should "create child actors for due schedules" in new DefaultRepositoryContext("test-user") {
    val testInstance = FlowInstanceContext(FlowInstanceDetails(
      id = "test-instance",
      flowDefinitionId = "flow-id",
      creationTime = 0,
      startTime = None,
      endTime = None,
      status = FlowInstanceStatus.Scheduled), Seq.empty)

    val testSchedule = FlowScheduleDetails(
      id = "test-schedule",
      expression = Some("0 1 * * *"), // daily at 1:00 am
      flowDefinitionId = "flow-id",
      flowTaskId = None,
      nextDueDate = None,
      enabled = Some(true),
      created = 0,
      updated = None,
      creator = "test",
      version = 0,
      backFill = None)
    val futureSchedules = Future.successful(Seq(testSchedule))

    (flowScheduleRepository.getFlowSchedules(_: Option[Boolean], _: Option[String])(_: RepositoryContext)).expects(*, *, *).returning(futureSchedules)
    (flowInstanceRepository.createFlowInstance(_: String, _: Seq[FlowInstanceContextValue], _: FlowInstanceStatus.FlowInstanceStatus)(_: RepositoryContext)).expects("flow-id", Seq.empty[FlowInstanceContextValue], FlowInstanceStatus.Scheduled, *).returning(Future.successful(testInstance))
    (flowScheduler.nextOccurrence _).expects(testSchedule, 0).returning(Right(1))
    (flowScheduleStateStore.setDueDate(_: String, _: Long)(_: RepositoryContext)).expects(testSchedule.id, 1, *).returning(Future.successful(()))

    dueScheduledFlowInstances(now = 0).futureValue
  }

  it should "respect the parallelism option" in {
    val flowDefinition: SysiphosDefinition = SysiphosDefinition(
      "ls-definition-id",
      Seq(CommandLineTask("ls-task-id", None, "ls")),
      parallelism = Some(1))

    val instances = Seq(
      FlowInstanceContext(FlowInstanceDetails(
        status = FlowInstanceStatus.Scheduled,
        id = "1",
        flowDefinitionId = flowDefinition.id,
        creationTime = 2L,
        startTime = None,
        endTime = None), Seq.empty),
      FlowInstanceContext(FlowInstanceDetails(
        status = FlowInstanceStatus.Scheduled,
        id = "2",
        flowDefinitionId = flowDefinition.id,
        creationTime = 2L,
        startTime = None,
        endTime = None), Seq.empty))

    (flowInstanceRepository.counts _).expects(
      Option(Seq(flowDefinition.id)),
      Option(Seq(FlowInstanceStatus.Running)),
      None).returning(Future.successful(Seq.empty)).twice()

    executeInstances(flowDefinition, instances).futureValue should have size 1
    executeInstances(flowDefinition.copy(parallelism = Some(2)), instances).futureValue should have size 2

    (flowInstanceRepository.counts _).expects(
      Option(Seq(flowDefinition.id)),
      Option(Seq(FlowInstanceStatus.Running)),
      None).returning(Future.successful(Seq(InstanceCount(flowDefinition.id, FlowInstanceStatus.Running.toString, 1))))

    executeInstances(flowDefinition.copy(parallelism = Some(2)), instances).futureValue should have size 1
  }

  it should "return new instances when applying the schedule" in {
    val backFillEnabled = testSchedule.copy(backFill = Some(true))

    (flowScheduler.nextOccurrence _).expects(backFillEnabled, 1).returning(Right(2))
    (flowScheduler.missedOccurrences _).expects(backFillEnabled, 1).returning(Right(List.empty))

    (flowScheduleStateStore.setDueDate(_: String, _: Long)(_: RepositoryContext))
      .expects(backFillEnabled.id, 2, *)
      .returning(Future.successful())

    (flowInstanceRepository.createFlowInstance(_: String, _: Seq[FlowInstanceContextValue], _: FlowInstanceStatus.FlowInstanceStatus)(_: RepositoryContext))
      .expects("flow-definition", Seq.empty[FlowInstanceContextValue], *, *)
      .returning(Future.successful(newInstance))

    applySchedule(backFillEnabled, 1).futureValue should be(Some(Seq(newInstance)))
  }

  it should "return missed occurrences only when back fill is enabled" in {
    (flowScheduler.nextOccurrence _).expects(testSchedule, 1).returning(Right(2))

    (flowScheduleStateStore.setDueDate(_: String, _: Long)(_: RepositoryContext))
      .expects(*, *, *)
      .returning(Future.successful())

    (flowInstanceRepository.createFlowInstance(_: String, _: Seq[FlowInstanceContextValue], _: FlowInstanceStatus.FlowInstanceStatus)(_: RepositoryContext))
      .expects("flow-definition", Seq.empty[FlowInstanceContextValue], *, *)
      .returning(Future.successful(newInstance))

    applySchedule(testSchedule, 1).futureValue should be(Some(Seq(newInstance)))
  }

  it should "set status to failed if instance can not be found during retry" in {
    val retryQuery = FlowTaskInstanceQuery(
      dueBefore = Some(0),
      status = Some(Seq(FlowTaskInstanceStatus.Retry)),
      limit = Some(retryBatchSize))

    lazy val flowTaskInstanceInRetry = FlowTaskInstanceDetails(
      id = "task-instance-id",
      flowInstanceId = "flowInstanceId",
      flowDefinitionId = "flowDefinitionId",
      taskId = "task-id",
      creationTime = 1l,
      startTime = None,
      endTime = None,
      retries = 0,
      status = FlowTaskInstanceStatus.Retry,
      retryDelay = 10,
      nextDueDate = Some(52),
      logId = "log-id")

    (flowTaskInstanceRepository.find(_: FlowTaskInstanceQuery)(_: RepositoryContext)).expects(retryQuery, *).returning(
      Future.successful(Seq(flowTaskInstanceInRetry)))

    val query = FlowInstanceQuery(
      flowDefinitionId = Some(flowTaskInstanceInRetry.flowDefinitionId),
      instanceIds = Some(Seq(flowTaskInstanceInRetry.flowInstanceId)),
      status = Some(Seq(FlowInstanceStatus.Running)))

    (flowInstanceRepository.findContext(_: FlowInstanceQuery)(_: RepositoryContext)).expects(query, *).returning(Future.successful(Seq.empty))

    (flowTaskInstanceRepository.setStatus(_: String, _: FlowTaskInstanceStatus.FlowTaskInstanceStatus, _: Option[Int], _: Option[Long])(_: RepositoryContext))
      .expects(*, FlowTaskInstanceStatus.Failed, *, *, *)
      .returning(Future.successful(None))

    executeRetries(0).unsafeRunSync()
  }

  override def executeInstance(instance: FlowInstanceContext, selectedTaskId: Option[String]): Future[FlowInstance] =
    Future.successful(instance.instance)

  override def executeRunning(
    running: FlowInstanceDetails,
    selectedTaskId: Option[String]): Future[Any] = Future.successful()
}
