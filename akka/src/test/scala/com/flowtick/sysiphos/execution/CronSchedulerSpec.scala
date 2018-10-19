package com.flowtick.sysiphos.execution

import com.flowtick.sysiphos.scheduler.FlowScheduleDetails
import org.scalatest.{ FlatSpec, Matchers }

class CronSchedulerSpec extends FlatSpec with Matchers {
  "Cron Scheduler" should "give next occurrence for cron schedule" in {
    val testSchedule = FlowScheduleDetails(
      id = "test-schedule",
      creator = "test",
      created = 0,
      version = 0,
      updated = None,
      expression = Some("0 * * ? * *"), // every minute on second 0
      flowDefinitionId = "test-flow-id",
      flowTaskId = None,
      nextDueDate = None,
      enabled = None,
      backFill = None)

    val next = CronScheduler.nextOccurrence(testSchedule, 0)
    next should be(Some(60))
  }

  it should "return all occurrences between to old schedule and now" in {
    val testSchedule = FlowScheduleDetails(
      id = "test-schedule",
      creator = "test",
      created = 0,
      version = 0,
      updated = None,
      expression = Some("0 * * ? * *"), // every minute on second 0
      flowDefinitionId = "test-flow-id",
      flowTaskId = None,
      nextDueDate = Some(70),
      enabled = None,
      backFill = None)

    val now = 190
    val nextBackFill = CronScheduler.missedOccurrences(testSchedule.copy(backFill = Some(true)), now)
    nextBackFill should be(Seq(120, 180))
  }

}
