package com.flowtick.sysiphos.ui.flow

import com.flowtick.sysiphos.flow.{ FlowDefinitionSummary, FlowInstanceStatus, InstanceCount }
import com.flowtick.sysiphos.ui.{ HtmlComponent, Layout, SysiphosApi }
import com.thoughtworks.binding.Binding.{ Constants, Vars }
import com.thoughtworks.binding.{ Binding, dom }
import org.scalajs.dom.html._

import scala.concurrent.ExecutionContext.Implicits.global

class FlowsComponent(sysiphosApi: SysiphosApi) extends HtmlComponent with RunFlowComponent with Layout {
  val flows: Vars[FlowDefinitionSummary] = Vars.empty[FlowDefinitionSummary]

  def loadDefinitions: Binding[Vars[FlowDefinitionSummary]] = Binding {
    sysiphosApi.getFlowDefinitions.foreach { response =>
      flows.value.clear()
      flows.value.append(response.definitions: _*)
    }

    flows
  }

  @dom
  def instanceCountButton(count: InstanceCount): Binding[Anchor] =
    <a href={ s"#/instances/filter/${count.flowDefinitionId}?status=${count.status}" } class={
      FlowInstanceStatus.withName(count.status) match {
        case FlowInstanceStatus.Scheduled | FlowInstanceStatus.Triggered => "btn btn-info"
        case FlowInstanceStatus.Running => "btn btn-warning"
        case FlowInstanceStatus.Failed => "btn btn-danger"
        case FlowInstanceStatus.Done => "btn btn-success"
        case _ => "btn btn-default"
      }
    }><strong>{ count.status }</strong>&nbsp;<span class="badge"> { count.count.toString } </span></a>

  @dom
  def flowRow(flow: FlowDefinitionSummary): Binding[TableRow] =
    <tr>
      <td><a href={ "#/flow/show/" + flow.id }> { flow.id }</a></td>
      <td>
        <div class="btn-group" data:role="group" data:aria-label="count-buttons">
          { Constants(flow.counts: _*).map(instanceCountButton(_).bind) }
          <a class="btn btn-default" href={ s"#/instances/filter/${flow.id}" }>
            <strong>all</strong>
            <span class="badge"><i class="fas fa-search"></i></span>
          </a>
        </div>
      </td>
      <td>
        { runLink(flow.id).bind }
      </td>
    </tr>

  @dom
  def flowTable: Binding[Table] = {
    <table class="table table-striped">
      <thead>
        <th>ID</th>
        <th>Recent Executions</th>
        <th>Actions</th>
      </thead>
      <tbody>
        {
          for (flow <- loadDefinitions.bind) yield flowRow(flow).bind
        }
      </tbody>
    </table>
  }

  @dom
  override def element: Binding[Div] =
    <div id="flows">
      <h3>Flows <a class="btn btn-default" href="#/flow/new"><i class="fas fa-plus"></i> Add</a> </h3>
      {
        flowTable.bind
      }
    </div>

}
