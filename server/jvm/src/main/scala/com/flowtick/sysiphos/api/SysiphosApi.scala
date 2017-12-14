package com.flowtick.sysiphos.api

import com.flowtick.sysiphos.api.SysiphosApi.ApiContext
import com.flowtick.sysiphos.api.resources.{ GraphIQLResources, UIResources }
import com.flowtick.sysiphos.flow.FlowDefinition
import com.flowtick.sysiphos.scheduler.FlowSchedule
import io.circe.Json
import sangria.ast.Document
import sangria.execution.Executor
import sangria.marshalling.circe._
import sangria.parser.QueryParser
import sangria.schema.{ Field, ListType, ObjectType, OptionType, _ }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

object SysiphosApi {
  trait ApiContext {
    def findFlowDefinition(id: String): Option[FlowDefinition]
    def findFlowDefinitions(): Future[Seq[FlowDefinition]]
    def findSchedule(id: String): Option[FlowSchedule]
    def findSchedules(): Future[Seq[FlowSchedule]]
  }

  val FlowDefinitionType = ObjectType(
    "FlowDefinition",
    "A flow definition",
    fields[Unit, FlowDefinition](
      Field("id", StringType, resolve = _.value.id)))

  val FlowScheduleType = ObjectType(
    "FlowSchedule",
    "A schedule for a flow",
    fields[Unit, FlowSchedule](
      Field("id", StringType, resolve = _.value.id)))

  val Id = Argument("id", StringType)

  val QueryType = ObjectType("Query", fields[ApiContext, Unit](
    Field(
      "definition",
      OptionType(FlowDefinitionType),
      description = Some("Returns the schedule with the `id`."),
      arguments = Id :: Nil,
      resolve = c => c.ctx.findFlowDefinition(c arg Id)),
    Field(
      "definitions",
      ListType(FlowDefinitionType),
      description = Some("Returns a list of all schedules."),
      resolve = _.ctx.findFlowDefinitions()),
    Field(
      "schedule",
      OptionType(FlowScheduleType),
      description = Some("Returns the schedule with the `id`."),
      arguments = Id :: Nil,
      resolve = c => c.ctx.findSchedule(c arg Id)),
    Field(
      "schedules",
      ListType(FlowScheduleType),
      description = Some("Returns a list of all schedules."),
      resolve = _.ctx.findSchedules())))

  val schema = Schema(QueryType)
}

trait SysiphosApi extends GraphIQLResources with UIResources {
  import io.finch._
  import io.finch.circe._

  def apiContext: ApiContext
  implicit val executionContext: ExecutionContext

  val statusEndpoint: Endpoint[String] = get("status") { Ok("OK") }

  val apiEndpoint: Endpoint[Json] = post("api" :: jsonBody[Json]) { json: Json =>
    val result: Future[Json] = json.asObject.flatMap { queryObj =>
      val query: Option[String] = queryObj("query").flatMap(_.asString)
      val operationName: Option[String] = queryObj("operationName").flatMap(_.asString)
      val variables: Json = queryObj("variables").filter(!_.isNull).getOrElse(Json.obj())

      query.map(parseQuery).map {
        case Success(document) => executeQuery(document, operationName, variables)
        case Failure(parseError) => Future.failed(parseError)
      }
    }.getOrElse(Future.failed(new IllegalArgumentException("invalid json body")))

    result.map(Ok).asTwitter
  }

  def parseQuery(query: String): Try[Document] = QueryParser.parse(query)

  def executeQuery(query: Document, operation: Option[String], vars: Json): Future[Json] =
    Executor.execute(SysiphosApi.schema, query, apiContext, variables = vars, operationName = operation)

  val api = statusEndpoint :+: apiEndpoint
}
