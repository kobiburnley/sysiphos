package com.flowtick.sysiphos.task

import com.flowtick.sysiphos.flow.FlowDefinition.ExtractSpec
import com.flowtick.sysiphos.flow.FlowTask

final case class RegistryEntry(`type`: String, fqn: String, properties: Option[Map[String, String]])

final case class CamelTask(
  id: String,
  children: Option[Seq[FlowTask]],
  uri: String,
  `type`: String = "camel",
  exchangeType: Option[String] = None,
  sendUri: Option[String] = None,
  receiveUri: Option[String] = None,
  pattern: Option[String] = None,
  bodyTemplate: Option[String] = None,
  headers: Option[Map[String, String]] = None,
  to: Option[Seq[String]] = None,
  extract: Option[Seq[ExtractSpec]] = None,
  convertStreamToString: Option[Boolean] = None,
  registry: Option[Map[String, RegistryEntry]] = None,
  startDelay: Option[Long] = None,
  retryDelay: Option[Long] = None,
  retries: Option[Int] = None,
  onFailure: Option[FlowTask] = None) extends FlowTask