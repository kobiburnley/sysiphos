package com.flowtick.sysiphos.slick

import com.flowtick.sysiphos.core.DefaultRepositoryContext
import com.flowtick.sysiphos.flow.FlowDefinition
import com.flowtick.sysiphos.flow.FlowDefinition.SysiphosDefinition
import com.flowtick.sysiphos.task.CommandLineTask
import slick.jdbc.H2Profile

import scala.util.Try

class SlickFlowDefinitionRepositorySpec extends SlickSpec {
  lazy val slickDefinitionRepository = new SlickFlowDefinitionRepository(dataSource)(H2Profile, scala.concurrent.ExecutionContext.Implicits.global)

  "Slick Definition Repository" should "create definition" in new DefaultRepositoryContext("test-user") {
    val simpleDefinition = SysiphosDefinition(
      "foo",
      Seq(CommandLineTask("foo", None, "ls -la")))

    Try(slickDefinitionRepository.getFlowDefinitions(this).futureValue).failed.foreach(_.printStackTrace())
    slickDefinitionRepository.createOrUpdateFlowDefinition(simpleDefinition)(this).futureValue.source.map(FlowDefinition.fromJson) should be(Some(Right(simpleDefinition)))
    slickDefinitionRepository.getFlowDefinitions(this).futureValue should have size 1
  }
}
