package com.flowtick.sysiphos.execution

import java.util.concurrent.{ Executors, TimeUnit }

import akka.actor.{ Actor, ActorRef }
import akka.pattern.{ ask, pipe }
import akka.util.Timeout
import com.flowtick.sysiphos.execution.FlowExecutorActor.{ NewInstance, RequestInstance }
import com.flowtick.sysiphos.execution.task.{ CamelTaskExecution, CommandLineTaskExecution }
import com.flowtick.sysiphos.flow.{ FlowInstance, FlowTaskInstance }
import com.flowtick.sysiphos.logging.Logger
import com.flowtick.sysiphos.task.{ CamelTask, CommandLineTask, TriggerFlowTask }

import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor, Future }
import scala.util.{ Failure, Success, Try }

class FlowTaskExecutionActor(
  taskInstance: FlowTaskInstance,
  flowInstance: FlowInstance,
  flowExecutorActor: ActorRef,
  taskLogger: Logger) extends Actor
  with CommandLineTaskExecution
  with CamelTaskExecution
  with Logging {

  implicit val taskExecutionContext: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newWorkStealingPool())

  override def receive: Receive = {
    case FlowTaskExecution.Execute(CommandLineTask(id, _, command, _, shell), logId) =>
      log.info(s"executing command with id $id")

      val tryRun: Try[Int] = for {
        finalCommand <- replaceContext(taskInstance, flowInstance, command)
        result <- runCommand(taskInstance, finalCommand, shell, logId)(taskLogger)
      } yield result

      tryRun match {
        case Failure(e) =>
          taskLogger.appendLine(logId, e.getMessage).unsafeRunSync()
          sender() ! FlowInstanceExecution.WorkFailed(e, taskInstance)
        case Success(_) => sender() ! FlowInstanceExecution.WorkDone(taskInstance)
      }

    case FlowTaskExecution.Execute(camelTask: CamelTask, logId) =>
      executeExchange(camelTask, flowInstance, logId)(taskLogger)
        .map(_.getOut.getBody)
        .unsafeToFuture()
        .map { result =>
          taskLogger.appendLine(logId, s"camel exchange executed with result: $result").unsafeRunSync()
          FlowInstanceExecution.WorkDone(taskInstance)
        }.recoverWith {
          case error =>
            taskLogger.appendLine(logId, s"error in camel exchange: ${error.getMessage}").unsafeRunSync()
            Future.successful(FlowInstanceExecution.WorkFailed(error, taskInstance))
        }.pipeTo(sender())

    case FlowTaskExecution.Execute(TriggerFlowTask(id, _, flowDefinitionId, _), logId) =>
      log.info(s"executing task with id $id")

      ask(flowExecutorActor, RequestInstance(flowDefinitionId, flowInstance.context))(Timeout(30, TimeUnit.SECONDS)).map {
        case NewInstance(Right(instance)) =>
          taskLogger.appendLine(logId, s"created ${instance.flowDefinitionId} instance ${instance.id}").unsafeRunSync()
          FlowInstanceExecution.WorkDone(taskInstance)
        case NewInstance(Left(error)) =>
          taskLogger.appendLine(logId, s"😞 unable to trigger instance $flowDefinitionId: ${error.getMessage}").unsafeRunSync()
          FlowInstanceExecution.WorkFailed(error, taskInstance)
      }.pipeTo(sender())

    case other: Any => sender() ! FlowInstanceExecution.WorkFailed(new IllegalStateException(s"unable to handle $other"), taskInstance)
  }

}
