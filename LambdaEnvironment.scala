package org.encalmo.lambda

import com.amazonaws.services.lambda.runtime.LambdaLogger
import com.amazonaws.services.lambda.runtime.logging.LogLevel
import org.encalmo.lambda.AnsiColor.*

import java.io.PrintStream
import java.net.URI
import java.net.http.*
import java.net.http.HttpRequest.BodyPublishers
import scala.collection.mutable
import scala.io.AnsiColor.*
import scala.jdk.CollectionConverters.*

/** Provides access to the lambda instance environment. */
final class LambdaEnvironment(
    variablesOverrides: Map[String, String] = Map.empty,
    retrieveSecrets: (String => Option[String]) => Map[String, String] = _ => Map.empty
) {

  export LambdaEnvironment.Logger.{info, debug, error, trace}

  def withAdditionalVariables(
      additionalVariablesOverrides: Map[String, String]
  ): LambdaEnvironment = new LambdaEnvironment(
    variablesOverrides ++ additionalVariablesOverrides
  )

  inline val LAMBDA_VERSION_DATE = "2018-06-01"

  private val systemVariables: mutable.Map[String, String] =
    System.getenv().asScala

  private val systemProperties: mutable.Map[String, String] =
    System.getProperties().asScala

  private val secrets = retrieveSecrets(key =>
    variablesOverrides
      .get(key)
      .orElse(systemProperties.get(key))
      .orElse(systemVariables.get(key))
  )

  /** The host and port of the runtime API. */
  private def getRuntimeApi(): String =
    getProperty("AWS_LAMBDA_RUNTIME_API")

  /** The name of the function. */
  final def getFunctionName(): String =
    getProperty("AWS_LAMBDA_FUNCTION_NAME")

  /** The version of the function being executed. */
  final def getFunctionVersion(): String =
    getProperty("AWS_LAMBDA_FUNCTION_VERSION")

  /** The amount of memory available to the function in MB. */
  final def getMemoryLimitInMB(): Int =
    getProperty("AWS_LAMBDA_FUNCTION_MEMORY_SIZE").toInt

  /** The name of the Amazon CloudWatch Logs group for the function. */
  final def getLogGroupName(): String =
    getProperty("AWS_LAMBDA_LOG_GROUP_NAME")

  /** The name of the Amazon CloudWatch Logs stream for the function. */
  final def getLogStreamName(): String =
    getProperty("AWS_LAMBDA_LOG_STREAM_NAME")

  /** The path to your Lambda function code. */
  final def getLambdaRuntimeDir(): String =
    getProperty("LAMBDA_RUNTIME_DIR")

  /** The path to runtime libraries. */
  final def getLambdaTaskRoot(): String =
    getProperty("LAMBDA_TASK_ROOT")

  /** Returns true when runs deployed to AWS Lambda */
  final val isHostedAwsEnvironment: Boolean =
    maybeGetProperty("_HANDLER").isDefined

  final val isDebugMode: Boolean =
    maybeGetProperty("LAMBDA_RUNTIME_DEBUG_MODE")
      .map(parseBooleanFlagDefaultOff)
      .getOrElse(true)

  final val isTraceMode: Boolean =
    maybeGetProperty("LAMBDA_RUNTIME_TRACE_MODE")
      .map(parseBooleanFlagDefaultOff)
      .getOrElse(!isHostedAwsEnvironment)

  final val shouldDisplayAnsiColors: Boolean =
    maybeGetProperty("ANSI_COLORS_MODE")
      .map(parseBooleanFlagDefault(!isHostedAwsEnvironment))
      .getOrElse(maybeGetProperty("NO_COLOR").forall(p => p.trim() != "1"))

  final val shouldLogStructuredJson: Boolean =
    !shouldDisplayAnsiColors &&
      maybeGetProperty("LAMBDA_RUNTIME_LOG_TYPE")
        .map(_.toUpperCase().contains("STRUCTURED"))
        .getOrElse(isHostedAwsEnvironment)

  final val shouldLogInJsonArrayFormat: Boolean =
    maybeGetProperty("LAMBDA_RUNTIME_LOG_FORMAT")
      .map(_.toUpperCase().contains("JSON_ARRAY"))
      .getOrElse(isHostedAwsEnvironment)

  final val shouldLogInJsonStringFormat: Boolean =
    maybeGetProperty("LAMBDA_RUNTIME_LOG_FORMAT")
      .map(_.toUpperCase().contains("JSON_STRING"))
      .getOrElse(false)

  final val shouldLogResponseIncludeRequest: Boolean =
    !shouldDisplayAnsiColors &&
      maybeGetProperty("LAMBDA_RUNTIME_LOG_RESPONSE_INCLUDE_REQUEST")
        .map(parseBooleanFlagDefaultOn)
        .getOrElse(true)

  final def maybeGetProperty(key: String): Option[String] =
    variablesOverrides
      .get(key)
      .orElse(systemProperties.get(key))
      .orElse(systemVariables.get(key))
      .orElse(secrets.get(key))

  final def getProperty(key: String): String =
    maybeGetProperty(key)
      .getOrElse(
        throw new Exception(
          s"Lambda environment property [$key] not found."
        )
      )

  final def getLogger(): LambdaLogger = SystemOutLambdaLogger

  lazy val lambdaOut: PrintStream =
    if (shouldDisplayAnsiColors)
    then LambdaEnvironment.originalOut
    else NoAnsiColors.getPrintStream(LambdaEnvironment.originalOut)

  final def setCustomOut(): Unit =
    System.setOut(lambdaOut)
    System.setErr(lambdaOut)

  final def resetOut(): Unit =
    System.setOut(LambdaEnvironment.originalOut)
    System.setErr(LambdaEnvironment.originalOut)

  final val nextEventUrl: URI =
    URI.create(
      s"http://${getRuntimeApi()}/$LAMBDA_VERSION_DATE/runtime/invocation/next"
    )

  final val nextEventRequest: HttpRequest =
    HttpRequest.newBuilder(nextEventUrl).GET().build()

  final val initErrorUrl: URI =
    URI.create(
      s"http://${getRuntimeApi()}/$LAMBDA_VERSION_DATE/runtime/init/error"
    )

  final def responseUrl(requestId: String): URI =
    URI.create(
      s"http://${getRuntimeApi()}/$LAMBDA_VERSION_DATE/runtime/invocation/$requestId/response"
    )

  final def responseRequest(requestId: String, output: String): HttpRequest =
    HttpRequest
      .newBuilder(responseUrl(requestId))
      .POST(BodyPublishers.ofString(output))
      .build()

  final def errorUrl(requestId: String): URI =
    URI.create(
      s"http://${getRuntimeApi()}/$LAMBDA_VERSION_DATE/runtime/invocation/$requestId/error"
    )

  final inline def parseBooleanFlagDefaultOff: String => Boolean = s =>
    s.trim().toLowerCase() match {
      case "true" => true
      case "on"   => true
      case _      => false
    }

  final inline def parseBooleanFlagDefaultOn: String => Boolean = s =>
    s.trim().toLowerCase() match {
      case "false" => false
      case "off"   => false
      case _       => true
    }

  final inline def parseBooleanFlagDefault(default: Boolean): String => Boolean = s =>
    s.trim().toLowerCase() match {
      case "false" => false
      case "off"   => false
      case _       => default
    }
}

object LambdaEnvironment {

  val originalOut: PrintStream = System.out
  object Logger {

    inline def info(
        message: String
    )(using lambdaEnvironment: LambdaEnvironment): Unit =
      if (lambdaEnvironment.shouldLogStructuredJson)
      then lambdaEnvironment.getLogger().log(message, LogLevel.INFO)
      else
        lambdaEnvironment
          .getLogger()
          .log(
            s"[${lambdaEnvironment.getFunctionName()}] $message",
            LogLevel.INFO
          )

    inline def debug(
        message: => String
    )(using lambdaEnvironment: LambdaEnvironment): Unit =
      if (lambdaEnvironment.isDebugMode)
      then
        if (lambdaEnvironment.shouldLogStructuredJson)
        then lambdaEnvironment.getLogger().log(message, LogLevel.DEBUG)
        else
          lambdaEnvironment
            .getLogger()
            .log(
              s"${PREFIX}[${lambdaEnvironment
                  .getFunctionName()}] $message${RESET}",
              LogLevel.DEBUG
            )
      else ()

    inline def trace(
        message: => String
    )(using lambdaEnvironment: LambdaEnvironment): Unit =
      if (lambdaEnvironment.isTraceMode)
      then
        if (lambdaEnvironment.shouldLogStructuredJson)
        then lambdaEnvironment.getLogger().log(message, LogLevel.TRACE)
        else
          lambdaEnvironment
            .getLogger()
            .log(
              s"${PREFIX}[${lambdaEnvironment
                  .getFunctionName()}] $message${RESET}",
              LogLevel.TRACE
            )
      else ()

    inline def error(
        message: String
    )(using lambdaEnvironment: LambdaEnvironment): Unit =
      if (lambdaEnvironment.shouldLogStructuredJson)
      then lambdaEnvironment.getLogger().log(message, LogLevel.ERROR)
      else
        lambdaEnvironment
          .getLogger()
          .log(
            s"${RED_B}${WHITE}[${lambdaEnvironment
                .getFunctionName()}][ERROR] $message${RESET}",
            LogLevel.ERROR
          )
  }
}
