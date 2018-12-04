package com.flowtick.sysiphos.execution

import java.time.{ LocalDateTime, ZoneId }

import cats.data.{ EitherT, OptionT }
import cats.instances.future._
import com.flowtick.sysiphos.core.RepositoryContext
import com.flowtick.sysiphos.flow._
import com.flowtick.sysiphos.scheduler.{ FlowSchedule, FlowScheduleRepository, FlowScheduleStateStore, FlowScheduler }

import scala.concurrent.{ ExecutionContext, Future }
import Logging._

trait FlowExecution extends Logging {
  val flowDefinitionRepository: FlowDefinitionRepository
  val flowScheduleRepository: FlowScheduleRepository
  val flowInstanceRepository: FlowInstanceRepository
  val flowScheduleStateStore: FlowScheduleStateStore
  val flowScheduler: FlowScheduler
  val flowTaskInstanceRepository: FlowTaskInstanceRepository

  implicit val repositoryContext: RepositoryContext
  implicit val executionContext: ExecutionContext

  def currentEpochSeconds: Long = LocalDateTime.now().atZone(ZoneId.systemDefault()).toEpochSecond

  def createFlowInstance(flowSchedule: FlowSchedule): Future[FlowInstance] = {
    log.debug(s"creating instance for $flowSchedule.")
    flowInstanceRepository.createFlowInstance(flowSchedule.flowDefinitionId, Seq.empty, FlowInstanceStatus.Scheduled)
  }

  def dueTaskRetries(now: Long): Future[Seq[(Option[FlowInstance], String)]] =
    flowTaskInstanceRepository.find(FlowTaskInstanceQuery(dueBefore = Some(now), status = Some(Seq(FlowTaskInstanceStatus.Retry)))).flatMap { tasks =>
      Future.sequence(tasks.map { task =>
        flowInstanceRepository.findById(task.flowInstanceId).map(instance => (instance, task.taskId))
      })
    }.logFailed(s"unable to check for retries")

  def manuallyTriggeredInstances: Future[Seq[FlowInstance]] =
    flowInstanceRepository.getFlowInstances(FlowInstanceQuery(
      flowDefinitionId = None,
      instanceIds = None,
      status = Some(Seq(FlowInstanceStatus.Triggered)),
      createdGreaterThan = None))

  def dueScheduledFlowInstances(now: Long): Future[Seq[FlowInstance]] = {
    log.debug("tick.")
    val futureEnabledSchedules: Future[Seq[FlowSchedule]] = flowScheduleRepository
      .getFlowSchedules(enabled = Some(true), None)

    for {
      schedules: Seq[FlowSchedule] <- futureEnabledSchedules
      applied <- Future.sequence(schedules.map(applySchedule(_, now)))
    } yield applied.flatten
  }

  def applySchedule(schedule: FlowSchedule, now: Long): Future[Seq[FlowInstance]] = {
    val potentialInstance: Future[Seq[FlowInstance]] = if (isDue(schedule, now))
      createFlowInstance(schedule).map(Seq(_))
    else Future.successful(Seq.empty)

    val missedInstances: Future[Seq[FlowInstance]] = if (schedule.backFill.contains(true))
      Future.sequence(flowScheduler.missedOccurrences(schedule, now).map { _ => createFlowInstance(schedule) })
    else Future.successful(Seq.empty)

    val potentialInstances = for {
      instance <- potentialInstance
      missed <- missedInstances
    } yield instance ++ missed

    potentialInstances.recoverWith {
      case error =>
        log.error("unable to create instance", error)
        Future.successful(Seq.empty)
    }.flatMap { instances =>
      flowScheduler.nextOccurrence(schedule, now).map { next =>
        setNextDueDate(schedule, next)
      }.getOrElse(Future.successful()).map { _ => instances }
    }
  }

  def setNextDueDate(schedule: FlowSchedule, next: Long): Future[Unit] =
    flowScheduleStateStore.setDueDate(schedule.id, next).recoverWith {
      case error =>
        log.error("unable to set due date", error)
        Future.successful(())
    }

  def isDue(schedule: FlowSchedule, now: Long): Boolean =
    schedule.nextDueDate match {
      case Some(timestamp) if timestamp <= now => true
      case None if schedule.enabled.contains(true) && schedule.expression.isDefined => true
      case _ => false
    }

  def latestOnly(definition: FlowDefinition, instancesToRun: Seq[FlowInstance]): Future[Seq[FlowInstance]] =
    if (definition.latestOnly) {
      val lastInstances: Seq[FlowInstance] = instancesToRun
        .groupBy(_.context.toSet)
        .flatMap { case (_, instances) => if (instances.isEmpty) None else Some(instances.maxBy(_.creationTime)) }
        .toSeq

      val olderInstances = instancesToRun.filterNot(lastInstances.contains)

      val skipOlderInstances: Future[Seq[FlowInstanceDetails]] = Future.sequence(
        olderInstances.map { instance =>
          flowInstanceRepository.setStatus(instance.id, FlowInstanceStatus.Skipped)
        }).map(_.flatten)

      skipOlderInstances.map(_ => lastInstances)
    } else Future.successful(instancesToRun)

  def executeInstances(
    flowDefinition: FlowDefinition,
    instancesToRun: Seq[FlowInstance]): Future[Seq[FlowInstance]] = {
    def runningInstances(flowDefinitionId: String): Future[Seq[InstanceCount]] =
      flowInstanceRepository.counts(Option(Seq(flowDefinitionId)), Option(Seq(FlowInstanceStatus.Running)), None)

    val withParallelism = runningInstances(flowDefinition.id).map { counts =>
      val runningInstances = counts
        .groupBy(_.flowDefinitionId)
        .getOrElse(flowDefinition.id, Seq.empty)
        .find(_.status == FlowInstanceStatus.Running.toString)
        .map(_.count)
        .getOrElse(0)

      val chosenInstances = for {
        parallelism <- Option(flowDefinition.parallelism.getOrElse(Integer.MAX_VALUE))
        newInstances <- Option(parallelism - runningInstances).filter(_ > 0)
      } yield instancesToRun.take(newInstances)

      chosenInstances.getOrElse(Seq.empty)
    }

    withParallelism.flatMap(instances => Future.sequence(instances.map(executeInstance(_, None))))
  }

  def executeInstance(instance: FlowInstance, selectedTaskId: Option[String]): Future[FlowInstance] = {
    val maybeFlowDefinition: EitherT[Future, String, FlowDefinition] = for {
      details <- EitherT.fromOptionF(flowDefinitionRepository.findById(instance.flowDefinitionId), s"unable to find definition for id ${instance.flowDefinitionId}")
      source <- EitherT.fromOption(details.source, s"source flow definition missing for id ${instance.flowDefinitionId}")
      parsedFlowDefinition <- EitherT.fromEither(FlowDefinition.fromJson(source)).leftMap(e => e.getLocalizedMessage)
    } yield parsedFlowDefinition

    val flowInstanceInit = maybeFlowDefinition.flatMap { definition =>
      val runningInstance = for {
        _ <- if (instance.startTime.isEmpty)
          flowInstanceRepository.setStartTime(instance.id, repositoryContext.epochSeconds)
        else
          Future.successful(())
        running <- flowInstanceRepository.setStatus(instance.id, FlowInstanceStatus.Running)
      } yield running

      EitherT
        .fromOptionF(runningInstance, "unable to update flow instance")
        .semiflatMap(instance => executeRunning(instance, definition, selectedTaskId).map(_ => instance))
    }

    flowInstanceInit.value.flatMap {
      case Left(message) => Future.failed(new IllegalStateException(message))
      case Right(value) => Future.successful(value)
    }
  }

  def executeRunning(
    running: FlowInstanceDetails,
    definition: FlowDefinition,
    selectedTaskId: Option[String]): Future[Any]

  def executeScheduled(): Unit = {
    val taskInstancesFuture: Future[Seq[FlowInstance]] = for {
      manuallyTriggered <- manuallyTriggeredInstances.logFailed("unable to get manually triggered instances")
      newTaskInstances <- dueScheduledFlowInstances(currentEpochSeconds).logFailed("unable to get scheduled flow instance")
    } yield newTaskInstances ++ manuallyTriggered

    for {
      instances <- taskInstancesFuture
      newInstances <- Future.successful(instances.groupBy(_.flowDefinitionId).toSeq.map {
        case (flowDefinitionId, triggeredInstances) =>
          val flowDefinitionFuture: Future[Option[FlowDefinitionDetails]] = flowDefinitionRepository.findById(flowDefinitionId)

          (for {
            flowDefinitionOrError: Either[Exception, FlowDefinition] <- OptionT(flowDefinitionFuture)
              .getOrElseF(Future.failed(new IllegalStateException("flow definition not found")))
              .map(FlowDefinition.apply)
            flowDefinition <- flowDefinitionOrError match {
              case Right(flowDefinition) => Future.successful(flowDefinition)
              case Left(error) => Future.failed(new IllegalStateException("unable to parse flow definition", error))
            }
            latestTriggered <- latestOnly(flowDefinition, triggeredInstances)
            running <- executeInstances(flowDefinition, latestTriggered)
          } yield running): Future[Seq[FlowInstance]]
      })
    } yield newInstances
  }

  def executeRetries(): Unit = {
    dueTaskRetries(currentEpochSeconds).logFailed("unable to get due tasks").foreach { dueTasks =>
      dueTasks.foreach {
        case (Some(instance), taskId) => executeInstance(instance, Some(taskId))
        case _ =>
      }
    }
  }
}