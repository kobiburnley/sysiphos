package com.flowtick.sysiphos.execution

import com.flowtick.sysiphos.flow._

trait FlowInstanceExecution extends Logging {
  protected def isFinished(instancesById: Map[String, Seq[FlowTaskInstance]], task: FlowTask): Boolean =
    instancesById.get(task.id) match {
      case Some(instances) => instances.forall(_.status == FlowTaskInstanceStatus.Done)
      case None => false
    }

  def nextFlowTasks(
    flowDefinition: FlowDefinition,
    taskInstances: Seq[FlowTaskInstance]): Seq[FlowTask] = {

    val instancesById = taskInstances.groupBy(_.taskId)

    val childrenOfDoneParents = Iterator.iterate(Seq(flowDefinition.task))(
      _.flatMap { task =>
        if (isFinished(instancesById, task))
          task.children.getOrElse(Seq.empty)
        else {
          Seq.empty
        }
      }).takeWhile(_.nonEmpty)

    childrenOfDoneParents.foldLeft(Seq.empty[FlowTask])(_ ++ _).filter(!isFinished(instancesById, _))
  }

}

object FlowInstanceExecution {
  sealed trait FlowInstanceMessage

  case class Execute(flowDefinition: FlowDefinition) extends FlowInstanceMessage
  case class WorkDone(flowTaskInstance: FlowTaskInstance) extends FlowInstanceMessage
  case class Retry(task: FlowTask, flowTaskInstance: FlowTaskInstance) extends FlowInstanceMessage
  case class Failed(flowInstance: FlowInstance) extends FlowInstanceMessage
  case class WorkFailed(e: Throwable, task: FlowTask, flowTaskInstance: FlowTaskInstance) extends FlowInstanceMessage
}