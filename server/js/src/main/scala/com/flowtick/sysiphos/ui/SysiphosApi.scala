package com.flowtick.sysiphos.ui

import com.flowtick.sysiphos.flow._
import com.flowtick.sysiphos.scheduler.FlowScheduleDetails
import io.circe.{ Decoder, Json }
import io.circe.generic.auto._
import io.circe.parser._
import org.scalajs.dom.ext.{ Ajax, AjaxException }

import scala.concurrent.{ ExecutionContext, Future }

case class FlowDefinitionDetailsResult(definition: Option[FlowDefinitionDetails])
case class FlowDefinitionList(definitions: Seq[FlowDefinitionSummary])
case class FlowScheduleList(schedules: Seq[FlowScheduleDetails])
case class FlowInstanceList(instances: Seq[FlowInstanceDetails])
case class IdResult(id: String)

case class OverviewQueryResult(instances: Seq[FlowInstanceDetails], taskInstances: Seq[FlowTaskInstanceDetails])
case class FlowInstanceOverview(instance: FlowInstanceDetails, tasks: Seq[FlowTaskInstanceDetails])

case class CreateOrUpdateFlowResult[T](createOrUpdateFlowDefinition: T)
case class CreateFlowScheduleResult[T](createFlowSchedule: T)
case class CreateInstanceResult[T](createInstance: T)
case class DeleteInstanceResult[T](deleteInstance: T)
case class DeleteTaskInstanceResult[T](deleteFlowTaskInstance: T)

case class EnableResult(enabled: Boolean)
case class BackFillResult(backFill: Boolean)
case class ExpressionResult(expression: String)
case class UpdateFlowScheduleResponse[T](updateFlowSchedule: T)
case class LogResult(log: String)

case class GraphQLResponse[T](data: T)

case class ErrorMessage(message: String)
case class ErrorResponse(errors: Seq[ErrorMessage])

trait SysiphosApi {
  def getFlowDefinitions: Future[FlowDefinitionList]

  def getFlowDefinition(id: String): Future[Option[FlowDefinitionDetails]]

  def createOrUpdateFlowDefinition(source: String): Future[Option[FlowDefinitionDetails]]

  def createFlowSchedule(flowId: String, expression: String): Future[FlowScheduleDetails]

  def getSchedules(flowId: Option[String]): Future[FlowScheduleList]

  def setFlowScheduleEnabled(
    id: String,
    enabled: Boolean): Future[Boolean]

  def setFlowScheduleBackFill(
    id: String,
    backFill: Boolean): Future[Boolean]

  def setFlowScheduleExpression(
    id: String,
    expression: String): Future[String]

  def getInstances(flowId: Option[String], status: Option[String], createdGreaterThan: Option[Long]): Future[FlowInstanceList]

  def getInstanceOverview(instanceId: String): Future[Option[FlowInstanceOverview]]

  def getLog(logId: String): Future[String]

  def createInstance(flowDefinitionId: String, contextValues: Seq[FlowInstanceContextValue]): Future[String]

  def deleteInstance(flowInstanceId: String): Future[String]

}

class SysiphosApiClient(implicit executionContext: ExecutionContext) extends SysiphosApi {
  import com.flowtick.sysiphos.ui.vendor.ToastrSupport._

  def quotedOrNull(optional: Option[String]): String = optional.map("\"" + _ + "\"").getOrElse("null")

  def query[T](query: String, variables: Map[String, Json] = Map.empty)(implicit ev: Decoder[T]): Future[GraphQLResponse[T]] = {
    val queryJson = Json.obj(
      "query" -> Json.fromString(query),
      "variables" -> Json.fromFields(variables)).noSpaces

    Ajax.post("/api", queryJson, headers = Map("Content-Type" -> "application/json")).flatMap(response => decode[GraphQLResponse[T]](response.responseText) match {
      case Right(parsed) =>
        Future.successful(parsed)
      case Left(error) =>
        println(s"error while process api query: ${error.getMessage}, ${response.responseText}, ${response.status}, ${response.statusText}")
        error.printStackTrace()
        Future.failed(error)
    }).transform(identity[GraphQLResponse[T]], error => {
      val transformedError = error match {
        case ajax: AjaxException =>
          val errorMessage: String = decode[ErrorResponse](ajax.xhr.responseText)
            .map(_.errors.map(_.message).mkString(", "))
            .getOrElse(ajax.xhr.responseText)

          new RuntimeException(errorMessage)
        case error: Throwable =>
          new RuntimeException(error.getMessage)
      }

      println(transformedError.getMessage)

      transformedError
    })
  }.notifyError

  override def getSchedules(flowId: Option[String]): Future[FlowScheduleList] =
    query[FlowScheduleList](
      s"""
         |{
         |  schedules (flowId: ${quotedOrNull(flowId)})
         |  {id, creator, created, version, flowDefinitionId, enabled, expression, nextDueDate, backFill }
         |}
         |
       """.stripMargin).map(_.data)

  override def getFlowDefinitions: Future[FlowDefinitionList] =
    query[FlowDefinitionList]("{ definitions {id, counts { status, count, flowDefinitionId } } }").map(_.data)

  override def getFlowDefinition(id: String): Future[Option[FlowDefinitionDetails]] = {
    query[FlowDefinitionDetailsResult](s"""{ definition(id: "$id") {id, version, source, created} }""").map(_.data.definition)
  }

  override def createOrUpdateFlowDefinition(source: String): Future[Option[FlowDefinitionDetails]] =
    parse(source) match {
      case Right(json) =>
        val createFlowQuery = s"""
                    |mutation {
                    |  createOrUpdateFlowDefinition(json: ${Json.fromString(json.noSpaces).noSpaces}) {
                    |    id, version, source, created
                    |  }
                    |}
                    |""".stripMargin
        query[CreateOrUpdateFlowResult[Option[FlowDefinitionDetails]]](createFlowQuery).map(_.data.createOrUpdateFlowDefinition)
      case Left(error) => Future.failed(error)
    }

  override def setFlowScheduleEnabled(
    id: String,
    enabled: Boolean): Future[Boolean] = {
    val queryString = s"""mutation { updateFlowSchedule(id: "$id", enabled: $enabled) { enabled } }"""
    query[UpdateFlowScheduleResponse[EnableResult]](queryString).map(_.data.updateFlowSchedule.enabled)
  }

  override def setFlowScheduleBackFill(
    id: String,
    backFill: Boolean): Future[Boolean] = {
    val queryString = s"""mutation { updateFlowSchedule(id: "$id", backFill: $backFill) { backFill } }"""
    query[UpdateFlowScheduleResponse[BackFillResult]](queryString).map(_.data.updateFlowSchedule.backFill)
  }

  override def setFlowScheduleExpression(
    id: String,
    expression: String): Future[String] = {
    val queryString = s"""mutation { updateFlowSchedule(id: "$id", expression: "$expression") { expression } }"""
    query[UpdateFlowScheduleResponse[ExpressionResult]](queryString).map(_.data.updateFlowSchedule.expression)
  }

  override def createFlowSchedule(flowId: String, expression: String): Future[FlowScheduleDetails] = {
    val createScheduleQuery =
      s"""
         |mutation {
         |  createFlowSchedule(flowDefinitionId: "$flowId", expression: "$expression") {
         |    id, creator, created, version, flowDefinitionId, enabled, expression, nextDueDate
         |  }
         |}
       """.stripMargin
    query[CreateFlowScheduleResult[FlowScheduleDetails]](createScheduleQuery).map(_.data.createFlowSchedule)
  }

  override def getInstances(flowId: Option[String], status: Option[String], createdGreaterThan: Option[Long]): Future[FlowInstanceList] = {
    val instancesQuery =
      s"""
         |{
         |  instances (flowDefinitionId: ${quotedOrNull(flowId)},
         |             status: ${quotedOrNull(status)},
         |             createdGreaterThan: ${createdGreaterThan.map(_.toString).getOrElse("null")}) {
         |    id, flowDefinitionId, creationTime, startTime, endTime, status, context {
         |      key, value
         |    }
         |  }
         |}
       """.stripMargin
    query[FlowInstanceList](instancesQuery).map(_.data)
  }

  override def getInstanceOverview(instanceId: String): Future[Option[FlowInstanceOverview]] = {
    val instanceOverviewQuery =
      s"""
         |{
         |  instances(instanceIds: ["$instanceId"]) {
         |    id, flowDefinitionId, creationTime, startTime, endTime, status, context {
         |      key, value
         |    }
         |  },
         |	taskInstances(flowInstanceId: "$instanceId") {
         |    id, flowInstanceId, taskId, creationTime, updatedTime, startTime, endTime, status, retries, retryDelay, logId, nextDueDate
         |  }
         |}
       """.stripMargin

    def resultToOverview(result: OverviewQueryResult): Option[FlowInstanceOverview] =
      result.instances.headOption.flatMap(details => {
        Some(FlowInstanceOverview(details, result.taskInstances))
      })

    query[OverviewQueryResult](instanceOverviewQuery).map(result => resultToOverview(result.data))
  }

  override def getLog(logId: String): Future[String] = {
    query[LogResult](s"""{ log (logId: "$logId") }""").map(_.data.log)
  }

  override def createInstance(flowDefinitionId: String, contextValues: Seq[FlowInstanceContextValue]): Future[String] = {
    val createInstanceQuery =
      s"""
         |mutation {
         |	createInstance(flowDefinitionId: "$flowDefinitionId", context: [
         |    ${contextValues.map(value => s""" {key: "${value.key}", value : "${value.value}"} """).mkString(",")}
         |  ]) {id}
         |}
       """.stripMargin
    query[CreateInstanceResult[IdResult]](createInstanceQuery).map(_.data.createInstance.id)
  }

  override def deleteInstance(flowInstanceId: String): Future[String] = {
    val deleteInstanceQuery =
      s"""
         |mutation {
         |	deleteInstance(flowInstanceId: "$flowInstanceId")
         |}
     """.stripMargin
    query[DeleteInstanceResult[String]](deleteInstanceQuery).map(_.data.deleteInstance)
  }

}
