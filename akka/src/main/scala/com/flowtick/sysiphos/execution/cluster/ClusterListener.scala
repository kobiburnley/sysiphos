package com.flowtick.sysiphos.execution.cluster

import akka.actor.{ Actor, ActorLogging }
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._

class ClusterListener extends Actor with ActorLogging {
  // subscribe to cluster changes, re-subscribe when restart
  override def preStart(): Unit = {
    log.debug("starting up cluster listener...")
    Cluster(context.system).subscribe(self, classOf[ClusterDomainEvent])
  }

  def receive: Actor.Receive = {
    case state: CurrentClusterState ⇒
      log.debug("Current members: {}", state.members.mkString(", "))
    case MemberUp(member) =>
      log.debug("Member is Up: {}", member.address)
    case UnreachableMember(member) =>
      log.debug("Member detected as unreachable: {}", member)
    case MemberRemoved(member, previousStatus) =>
      log.debug(
        "Member is Removed: {} after {}",
        member.address, previousStatus)
    case LeaderChanged(member) => log.info("Leader changed: " + member)
    case any: MemberEvent => log.info("Member Event: " + any.toString) // ignore
  }
}