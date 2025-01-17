package com.flowtick.sysiphos.execution

import com.flowtick.sysiphos.core.RepositoryContext
import com.flowtick.sysiphos.flow.FlowDefinition.SysiphosDefinition
import com.flowtick.sysiphos.flow._
import com.flowtick.sysiphos.logging.ConsoleLogger
import com.flowtick.sysiphos.task.CommandLineTask
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FlatSpec, Matchers }

import scala.concurrent.Future

class FlowInstanceExecutionSpec extends FlatSpec
  with FlowInstanceExecution
  with Matchers
  with ScalaFutures
  with MockFactory {
  val flowInstanceRepository: FlowInstanceRepository = mock[FlowInstanceRepository]
  val flowTaskInstanceRepository: FlowTaskInstanceRepository = mock[FlowTaskInstanceRepository]

  "Flow Instance Execution" should "find next tasks" in {
    val cmd4 = CommandLineTask("cmd4", None, "ls 4")

    val secondLevelChildren = Seq(cmd4)
    val cmd3 = CommandLineTask("cmd3", Some(secondLevelChildren), "ls 3")

    val firstLevelChildren = Seq(
      CommandLineTask("cmd2", None, "ls 2"),
      cmd3)

    val root = CommandLineTask("cmd1", Some(firstLevelChildren), "ls 1")

    val flowDefinition = SysiphosDefinition(
      "test", Seq(root))

    val doneTaskInstance = FlowTaskInstanceDetails(
      id = "taskInstanceId",
      flowInstanceId = "flowInstanceId",
      flowDefinitionId = "flowDefinitionId",
      taskId = "cmd1",
      0, None, None, None, None, 3,
      FlowTaskInstanceStatus.Done, 10, None, "log-id")

    nextFlowTasks(PendingTasks, flowDefinition, Seq.empty) should be(Seq(root))
    nextFlowTasks(PendingTasks, flowDefinition, Seq(doneTaskInstance)) should be(firstLevelChildren)
    nextFlowTasks(PendingTasks, flowDefinition, Seq(doneTaskInstance, doneTaskInstance.copy(taskId = "cmd2"))) should be(Seq(cmd3))
    nextFlowTasks(PendingTasks, flowDefinition, Seq(doneTaskInstance, doneTaskInstance.copy(taskId = "cmd2"), doneTaskInstance.copy(taskId = "cmd3"))) should be(Seq(cmd4))
  }

  it should "create a task instance with delay applied" in new RepositoryContext {
    def currentUser: String = "test-user"
    def epochSeconds: Long = 42

    val task = CommandLineTask(
      "ls-task-id",
      None,
      "ls", startDelay = Some(10))
    val flowDefinition: SysiphosDefinition = SysiphosDefinition("ls-definition-id", Seq(task))

    lazy val flowInstance = FlowInstanceDetails(
      status = FlowInstanceStatus.Scheduled,
      id = "???",
      flowDefinitionId = flowDefinition.id,
      creationTime = 1L,
      startTime = None,
      endTime = None)

    lazy val flowTaskInstance = FlowTaskInstanceDetails(
      id = "task-id",
      flowInstanceId = flowInstance.id,
      flowDefinitionId = "flowDefinitionId",
      taskId = flowDefinition.tasks.head.id,
      creationTime = 1l,
      startTime = None,
      endTime = None,
      retries = 0,
      status = FlowTaskInstanceStatus.New,
      retryDelay = 10,
      nextDueDate = Some(52),
      logId = "log-id")

    (flowTaskInstanceRepository.findOne(_: FlowTaskInstanceQuery)(_: RepositoryContext))
      .expects(FlowTaskInstanceQuery(flowInstanceId = Some(flowInstance.id), taskId = Some(flowDefinition.tasks.head.id)), *)
      .returning(Future.successful(None))

    (flowTaskInstanceRepository.createFlowTaskInstance(_: String, _: String, _: String, _: String, _: Int, _: Long, _: Option[Long], _: Option[String], _: Option[FlowTaskInstanceStatus.FlowTaskInstanceStatus])(_: RepositoryContext))
      .expects(flowInstance.id, *, *, *, *, *, Some(52L), *, *, *)
      .returning(Future.successful(flowTaskInstance))

    getOrCreateTaskInstance(
      flowTaskInstanceRepository,
      flowInstance.id,
      flowDefinition.id,
      flowDefinition.tasks.head,
      new ConsoleLogger)(this).unsafeRunSync()
  }

  it should "create a task instance with on failure task" in new RepositoryContext {
    def currentUser: String = "test-user"
    def epochSeconds: Long = 42

    val task = CommandLineTask(
      id = "ls-task-id",
      children = None,
      command = "ls",
      onFailure = Some(CommandLineTask(
        id = "failure-task-id",
        children = None,
        command = "echo 'failureeeee'")))
    val flowDefinition: SysiphosDefinition = SysiphosDefinition("ls-definition-id", Seq(task))

    lazy val flowInstance = FlowInstanceDetails(
      status = FlowInstanceStatus.Scheduled,
      id = "???",
      flowDefinitionId = flowDefinition.id,
      creationTime = 1L,
      startTime = None,
      endTime = None)

    lazy val flowTaskInstance = FlowTaskInstanceDetails(
      id = "task-id",
      flowInstanceId = flowInstance.id,
      flowDefinitionId = "flowDefinitionId",
      taskId = flowDefinition.tasks.head.id,
      creationTime = 1l,
      onFailureTaskId = Some("failure-task-id"),
      startTime = None,
      endTime = None,
      retries = 0,
      status = FlowTaskInstanceStatus.New,
      retryDelay = 0,
      nextDueDate = None,
      logId = "log-id")

    (flowTaskInstanceRepository.findOne(_: FlowTaskInstanceQuery)(_: RepositoryContext))
      .expects(FlowTaskInstanceQuery(flowInstanceId = Some(flowInstance.id), taskId = Some(flowDefinition.tasks.head.id)), *)
      .returning(Future.successful(None))

    (flowTaskInstanceRepository.createFlowTaskInstance(_: String, _: String, _: String, _: String, _: Int, _: Long, _: Option[Long], _: Option[String], _: Option[FlowTaskInstanceStatus.FlowTaskInstanceStatus])(_: RepositoryContext))
      .expects(flowInstance.id, *, *, *, *, *, *, Some("failure-task-id"), *, *)
      .returning(Future.successful(flowTaskInstance))

    getOrCreateTaskInstance(
      flowTaskInstanceRepository,
      flowInstance.id,
      flowDefinition.id,
      flowDefinition.tasks.head,
      new ConsoleLogger)(this).unsafeRunSync()
  }

}
