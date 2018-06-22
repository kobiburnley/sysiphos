package com.flowtick.sysiphos.flow

import com.flowtick.sysiphos.core.RepositoryContext

import scala.concurrent.Future

final case class FlowInstanceQuery(flowDefinitionId: String)
final case class InstanceCount(flowDefinitionId: String, status: String, count: Int)
final case class FlowDefinitionSummary(id: String, counts: Seq[InstanceCount])

trait FlowInstanceRepository[T <: FlowInstance] {
  def getFlowInstances(query: FlowInstanceQuery)(implicit repositoryContext: RepositoryContext): Future[Seq[T]]
  def createFlowInstance(flowDefinitionId: String, context: Map[String, String])(implicit repositoryContext: RepositoryContext): Future[T]
  def counts(flowDefinitionId: Option[Seq[String]], status: Option[Seq[String]]): Future[Seq[InstanceCount]]
}

