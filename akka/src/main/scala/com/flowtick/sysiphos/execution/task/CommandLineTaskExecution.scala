package com.flowtick.sysiphos.execution.task

import java.io.{ File, FileOutputStream }

import cats.effect.{ ContextShift, IO }
import com.flowtick.sysiphos.config.Configuration
import com.flowtick.sysiphos.core.Clock
import com.flowtick.sysiphos.execution.FlowTaskExecution
import com.flowtick.sysiphos.flow.{ FlowInstanceContextValue, FlowTaskInstance }
import com.flowtick.sysiphos.logging.Logger
import com.flowtick.sysiphos.logging.Logger.LogId

import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.util.Try
import scala.sys.process._

trait CommandLineTaskExecution extends FlowTaskExecution with Clock {
  implicit val taskExecutionContext: ExecutionContextExecutor

  def createScriptFile(taskInstance: FlowTaskInstance, command: String): File = {
    val tempDir = sys.props.get("java.io.tempdir").getOrElse(Configuration.propOrEnv("backup.temp.dir", "/tmp"))
    val scriptFile = new File(tempDir, s"script_${taskInstance.id}.sh")
    val scriptOutput = new FileOutputStream(scriptFile)

    scriptOutput.write(command.getBytes("UTF-8"))
    scriptOutput.flush()
    scriptOutput.close()
    scriptFile
  }

  def replaceContext(
    taskInstance: FlowTaskInstance,
    contextValues: Seq[FlowInstanceContextValue],
    command: String): Try[String] = {
    val creationDateTime = fromEpochSeconds(taskInstance.creationTime)
    val additionalModel = sanitizedSysProps ++ Map("creationTime" -> creationDateTime)

    replaceContextInTemplate(command, contextValues, additionalModel)
  }

  private def commandIO(
    command: String,
    logId: LogId,
    logQueue: fs2.concurrent.Queue[IO, Option[String]])(taskLogger: Logger): IO[Int] = IO.async[Int] { callback =>
    val runningCommand = Future(command.!(ProcessLogger(out => {
      logQueue.enqueue1(Some(out)).unsafeRunSync()
    })))

    runningCommand.foreach(exitCode => {
      logQueue.enqueue1(None).unsafeRunSync()
      callback(Right(exitCode))
    })

    runningCommand.failed.foreach(error => {
      logQueue.enqueue1(None).unsafeRunSync()
      callback(Left(error))
    })

    taskLogger.appendStream(logId, logQueue.dequeue.unNoneTerminate).unsafeRunSync()
  }

  def runCommand(
    taskInstance: FlowTaskInstance,
    command: String,
    shellOption: Option[String],
    logId: LogId)(taskLogger: Logger): IO[Int] = IO.unit.flatMap { _ =>
    import IO._
    implicit val contextShift: ContextShift[IO] = cats.effect.IO.contextShift(taskExecutionContext)

    val scriptFile = createScriptFile(taskInstance, command)

    val commandLine = shellOption.map { shell =>
      s"$shell ${scriptFile.getAbsolutePath}"
    }.getOrElse(command)

    taskLogger.appendLine(logId, s"\n### running '$command' via '$commandLine'").unsafeRunSync()

    val queueSize = Configuration.propOrEnv("logger.stream.queueSize", "1000").toInt

    val finishedProcess: IO[Int] = fs2.concurrent.Queue
      .circularBuffer[IO, Option[String]](queueSize)
      .flatMap(commandIO(commandLine, logId, _)(taskLogger))

    finishedProcess.guarantee(IO.delay(scriptFile.delete()))
  }.flatMap { exitCode =>
    if (exitCode != 0) {
      IO.raiseError(new RuntimeException(s"😞 got failure code during execution: $exitCode"))
    } else IO.pure(exitCode)
  }
}
