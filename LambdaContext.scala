package org.encalmo.lambda

import com.amazonaws.services.lambda.runtime.ClientContext
import com.amazonaws.services.lambda.runtime.CognitoIdentity
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.LambdaLogger
import com.amazonaws.services.lambda.runtime.logging.LogLevel
import org.encalmo.lambda.AnsiColor.*

import scala.io.AnsiColor.*
import scala.jdk.OptionConverters.*

/** Lambda invocation context data. */
final case class LambdaContext(
    requestId: String,
    headers: Map[String, String],
    lambdaEnvironment: LambdaEnvironment,
    switchOffDebugMode: Function0[Unit]
) extends Context {

  export LambdaContext.Logger.{info, debug, error, trace}

  def addProperties(
      properties: Map[String, String]
  ): LambdaContext =
    copy(lambdaEnvironment = lambdaEnvironment.withAdditionalVariables(properties))

  export lambdaEnvironment.{
    getFunctionName,
    getFunctionVersion,
    getLogGroupName,
    getLogStreamName,
    getMemoryLimitInMB,
    getLogger
  }

  final def maybeGetProperty(key: String): Option[String] =
    lambdaEnvironment
      .maybeGetProperty(key)
      .orElse(headers.get(key))

  final def getProperty(key: String): String =
    maybeGetProperty(key)
      .getOrElse(
        throw new Exception(s"Lambda invocation property [$key] not found.")
      )

  override def getAwsRequestId(): String = requestId

  override def getRemainingTimeInMillis(): Int =
    getProperty("Lambda-Runtime-Deadline-Ms").toInt

  override def getInvokedFunctionArn(): String =
    getProperty("Lambda-Runtime-Invoked-Function-Arn")

  override def getIdentity(): CognitoIdentity =
    throw new UnsupportedOperationException()

  override def getClientContext(): ClientContext =
    throw new UnsupportedOperationException()
}

object LambdaContext {

  /** Returns AWS Region of the current lambda instance. */
  object Logger {

    inline def info(
        message: String
    )(using lambdaContext: LambdaContext): Unit =
      lambdaContext
        .getLogger()
        .log(
          s"[${lambdaContext.getFunctionName()}] $message",
          LogLevel.INFO
        )

    inline def debug(
        message: String
    )(using lambdaContext: LambdaContext): Unit =
      if (lambdaContext.lambdaEnvironment.isDebugMode)
      then
        lambdaContext
          .getLogger()
          .log(
            s"${PREFIX}[${lambdaContext.getFunctionName()}] $message${RESET}",
            LogLevel.DEBUG
          )
      else ()

    inline def trace(
        message: String
    )(using lambdaContext: LambdaContext): Unit =
      if (lambdaContext.lambdaEnvironment.isTraceMode)
      then
        lambdaContext
          .getLogger()
          .log(
            s"${PREFIX}[${lambdaContext.getFunctionName()}] $message${RESET}",
            LogLevel.TRACE
          )
      else ()

    inline def error(
        message: String
    )(using lambdaContext: LambdaContext): Unit =
      lambdaContext
        .getLogger()
        .log(
          s"${RED_B}${WHITE}[${lambdaContext
              .getFunctionName()}][ERROR] $message${RESET}",
          LogLevel.ERROR
        )
  }
}
