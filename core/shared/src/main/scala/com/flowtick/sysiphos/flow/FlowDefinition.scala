package com.flowtick.sysiphos.flow

import com.flowtick.sysiphos.task.{ CommandLineTask, TriggerFlowTask }
import io.circe.Decoder.Result
import io.circe._
import com.flowtick.sysiphos._

import scala.util.Either

trait FlowTask {
  def id: String
  def children: Option[Seq[FlowTask]]
}

trait FlowDefinition {
  def id: String
  def task: FlowTask

  def findTask(id: String): Option[FlowTask] = {
    def findInTask(task: FlowTask): Option[FlowTask] =
      if (task.id == id) {
        Some(task)
      } else task
        .children
        .getOrElse(Seq.empty)
        .map(task => findInTask(task))
        .flatMap(result => result.find(_.id == id))
        .headOption

    findInTask(task)
  }
}

object FlowDefinition {
  import io.circe.generic.auto._
  import io.circe.parser._
  import io.circe.syntax._

  implicit val definitionDecoder: Decoder[FlowDefinition] = new Decoder[FlowDefinition] {
    override def apply(c: HCursor): Result[FlowDefinition] = for {
      id <- c.downField("id").as[String]
      task <- c.downField("task").as[FlowTask]
    } yield SysiphosDefinition(id, task)
  }

  implicit val definitionEncoder: Encoder[FlowDefinition] = new Encoder[FlowDefinition] {
    override def apply(a: FlowDefinition): Json = Json.obj()
  }

  implicit val taskDecoder: Decoder[FlowTask] = new Decoder[FlowTask] {
    override def apply(c: HCursor): Result[FlowTask] = for {
      id <- c.downField("id").as[String]
      typeHint <- c.downField("type").as[String]
      task <- taskFromCursor(typeHint, c)
    } yield task
  }

  implicit val taskEncoder: Encoder[FlowTask] = new Encoder[FlowTask] {
    override def apply(a: FlowTask): Json = a match {
      case task: CommandLineTask => task.asJson
      case task: TriggerFlowTask => task.asJson
      case task: SysiphosTask => task.asJson
      case _ => Json.obj()
    }
  }

  def taskFromCursor(typeHint: String, cursor: HCursor): Either[DecodingFailure, FlowTask] = {
    typeHint match {
      case "shell" => cursor.as[CommandLineTask]
      case "trigger" => cursor.as[TriggerFlowTask]
      case _ => cursor.as[SysiphosTask]
    }
  }

  final case class SysiphosDefinition(id: String, task: FlowTask) extends FlowDefinition
  final case class SysiphosTask(
    id: String,
    `type`: String,
    children: Option[Seq[FlowTask]],
    properties: Option[Map[String, String]]) extends FlowTask

  def fromJson(json: String): Either[Exception, FlowDefinition] = decode[FlowDefinition](json)

  def toJson(definition: FlowDefinition): String = definition match {
    case s: SysiphosDefinition => s.asJson.spaces2
  }
}

