package org.encalmo.lambda

import com.amazonaws.services.lambda.runtime.ClientContext
import com.amazonaws.services.lambda.runtime.CognitoIdentity
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.LambdaLogger
import com.amazonaws.services.lambda.runtime.logging.LogLevel
import org.encalmo.lambda.LambdaRuntime.AnsiColor.*

import java.io.PrintStream
import java.net.URI
import java.net.http.*
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.io.AnsiColor
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.reflect.ClassTag
import scala.util.control.NonFatal

/** Custom lambda runtime base. (https://docs.aws.amazon.com/lambda/latest/dg/runtimes-custom.html)
  */
trait LambdaRuntime extends EventHandler, EventHandlerTag {

  import LambdaEnvironment.Logger.*

  /** Handle of the current lambda instance. */
  private val instance: AtomicReference[Option[Instance]] =
    new AtomicReference(None)

  /** Handle of the current test environment instance. */
  private val testLambdaEnvironment: AtomicReference[Option[LambdaEnvironment]] =
    new AtomicReference(None)

  /** Lambda configuration method. Provide your setup logic here.
    *
    * IN ORDER TO WORK MUST BE DECLARED USING THE FOLLOWING TEMPLATE:
    * @example
    *   lazy val config: MyConfig = configure{(lambdaEnvironment: LambdaEnvironment) => MyConfig(...)}
    */
  final def configure[T](f: LambdaEnvironment ?=> T): T =
    instance.getAcquire() match {
      case Some(i) =>
        debug("Configuring lambda ...")(using i.lambdaEnvironment)
        f(using i.lambdaEnvironment)
      case None =>
        testLambdaEnvironment.getAcquire() match {
          case Some(le) =>
            debug("Configuring lambda using test environment ...")(using le)
            f(using le)
          case None =>
            throw new IllegalStateException(
              "Lambda instance is not initialized yet at this point. Use `lazy val config = configure(...)` declaration."
            )
        }
    }

  val ZoneUTC = ZoneId.of("UTC")

  private lazy val httpClient = HttpClient
    .newBuilder()
    .connectTimeout(java.time.Duration.ofSeconds(60))
    .build()

  /** Per-invocation debug mode reset each time to global isDebugMode */
  private var lambdaInvocationDebugMode: Boolean = true

  /** Switch off debug mode for the current lambda invocation. */
  def switchOffDebugMode() = {
    lambdaInvocationDebugMode = false
  }

  /** Lambda runtime instance interface. */
  trait Instance {

    val runtimeId: String

    lazy val lambdaEnvironment: LambdaEnvironment

    /** Start receiving events. */
    def start(): Instance

    /** Wait for lambda runtime to finish job (potentially never). */
    def waitUntilInterrupted(): Instance

    /** Temporaily stop receiving events. */
    def pause(): Instance

    /** Stop receiving events and shutdow the runtime. */
    def shutdown(): Instance

  }

  /** Starts lambda runtime and blocks thread until finished. */
  final inline def run(): Unit = {
    initializeLambdaRuntime().start().waitUntilInterrupted()
  }

  /** Creates lambda runtime instance. */
  final def initializeLambdaRuntime(
      variablesOverrides: Map[String, String] = Map.empty
  ): Instance = {

    instance.getAcquire().match {
      case Some(i) => i // returns an existing instance if exists
      case None =>
        try {
          // Create and initialize a new instance of the lambda runtime
          val i = new Instance {

            val runtimeId = UUID.randomUUID().toString().take(6)

            given lambdaEnvironment: LambdaEnvironment =
              new LambdaEnvironment(variablesOverrides)

            lambdaEnvironment.setCustomOut()

            private val semaphore = new Semaphore(1)
            semaphore.acquire() // pausing execution until started
            private val active = new AtomicBoolean(true)
            private val counter = new AtomicInteger()

            private val mainLoop = new Runnable {
              def run =
                while (active.get()) {
                  val id = counter.incrementAndGet()
                  try {
                    semaphore.acquire()
                    lambdaInvocationDebugMode = lambdaEnvironment.isDebugMode
                    invokeHandleRequest(id)
                    trace(s"[$id] Done.")
                  } catch {
                    case e: InterruptedException =>
                    case e: HttpConnectTimeoutException =>
                      error(s"[$id] $e")
                      shutdown()
                    case e =>
                      if (active.get)
                      then
                        try {
                          reportError(createErrorMessage(e), lambdaEnvironment.initErrorUrl)
                        } catch {
                          case e => error(createErrorMessage(e))
                        }
                  } finally {
                    lambdaInvocationDebugMode = lambdaEnvironment.isDebugMode
                    Runtime.getRuntime().gc()
                    semaphore.release()
                  }
                }
            }

            private val loopThread = Thread.ofVirtual
              .name(
                s"lambda-runtime-${lambdaEnvironment.getFunctionName()}-$runtimeId"
              )
              .start(mainLoop)

            final override def start(): Instance = {
              semaphore.release()
              debug(s"[$runtimeId] LambdaRuntime started.")
              this
            }

            final override def waitUntilInterrupted(): Instance = {
              trace(s"Waiting ...")
              try (loopThread.join())
              catch {
                case e: InterruptedException =>
                  debug(s"[$runtimeId] LambdaRuntime interrupted.")
                  instance.setRelease(None)
              }
              this
            }

            final override def pause(): Instance = {
              semaphore.acquire()
              debug(s"[$runtimeId] LambdaRuntime paused.")
              this
            }

            final override def shutdown(): Instance = {
              debug(s"[$runtimeId] LambdaRuntime shutdowns.")
              active.set(false)
              lambdaEnvironment.resetOut()
              Thread.sleep(100)
              loopThread.interrupt()
              this
            }

            debug(s"[$runtimeId] LambdaRuntime initialized.")
          }
          instance.setRelease(Some(i))
          i
        } catch {
          // Lambda runtime initilization has failed, report error to the AWS host
          case NonFatal(e) =>
            given le: LambdaEnvironment = new LambdaEnvironment()
            try {
              reportError(createErrorMessage(e), le.initErrorUrl)
            } catch {
              case e => error(createErrorMessage(e))
            }
            le.resetOut()
            throw e
        }
    }
  }

  /** The actual lambda invocation happens here. */
  private final def invokeHandleRequest(
      id: Int
  )(using lambdaEnvironment: LambdaEnvironment): Unit = {
    lambdaEnvironment.setCustomOut()

    val functionName = lambdaEnvironment.getFunctionName()
    val functionVersion = lambdaEnvironment.getFunctionVersion()

    val uri = lambdaEnvironment.nextEventUrl
    trace(s"[$id] Requesting next event from $uri")

    val event = httpClient
      .send(lambdaEnvironment.nextEventRequest, BodyHandlers.ofString())

    val requestId =
      event.headers
        .firstValue("Lambda-Runtime-Aws-Request-Id")
        .toScala
        .getOrElse(
          throw new Exception(
            s"[$id] Missing [Lambda-Runtime-Aws-Request-Id] header. Skipping lambda execution."
          )
        )

    val tagOpt: Option[String] = getEventHandlerTag(event.body)
    val tag: String = tagOpt.map(t => s" [$t]").getOrElse("")

    val structuredLogIntro =
      s""""lambda":"${functionName}"${tagOpt
          .map(tag => s""","handler":"$tag"""")
          .getOrElse("")},"id":$id"""

    val structuredLogEnd =
      s""""lambdaVersion":"${functionVersion}","lambdaRequestId":"$requestId""""

    val isJsonRequest = {
      val body = event.body.trim()
      (body.startsWith("{") && body.endsWith("}")) || (body.startsWith("[") && body.endsWith("]"))
    }

    val t0 = System.currentTimeMillis()

    debug(
      if (lambdaEnvironment.shouldLogStructuredJson)
      then
        s"""{"log":"REQUEST",$structuredLogIntro,"request":${
            if (isJsonRequest) then event.body else s"\"${event.body.replace("\"", "\\\"")}\""
          },$structuredLogEnd,"timestamp":"${t0}","datetime":"${ZonedDateTime
            .now(ZoneUTC)
            .toString()}","maxMemory":${Runtime
            .getRuntime()
            .maxMemory()},"totalMemory":${Runtime
            .getRuntime()
            .totalMemory()},"freeMemory":${Runtime.getRuntime().freeMemory()}}"""
      else s"[$id]$tag ${REQUEST}LAMBDA REQUEST ${AnsiColor.BOLD}${event.body}"
    )

    try {
      val context = LambdaContext(
        requestId,
        event.headers
          .map()
          .asScala
          .map((key, values) => (key, values.getFirst()))
          .toMap,
        lambdaEnvironment,
        switchOffDebugMode
      )

      event.headers
        .firstValue("Lambda-Runtime-Aws-Request-Id")
        .toScala
        .foreach(xamazTraceId => System.setProperty("com.amazonaws.xray.traceHeader", xamazTraceId))

      val input = event.body

      val logPrinter: PrintStream | NoAnsiColorJsonArray | NoAnsiColorJsonString | NoAnsiColorsSingleLine =
        val logPrefix =
          if (lambdaEnvironment.shouldLogStructuredJson)
          then s"""{"log":"LOGS",$structuredLogIntro,"""
          else s"[${lambdaEnvironment.getFunctionName()}] [$id]$tag LAMBDA LOGS {"

        val logSuffix =
          if (lambdaEnvironment.shouldLogStructuredJson)
          then s""",$structuredLogEnd}"""
          else "}"

        if (lambdaInvocationDebugMode) then {
          if (lambdaEnvironment.shouldDisplayAnsiColors)
          then LambdaEnvironment.originalOut
          else if (lambdaEnvironment.shouldLogInJsonArrayFormat)
          then new NoAnsiColorJsonArray(logPrefix, logSuffix, LambdaEnvironment.originalOut)
          else if (lambdaEnvironment.shouldLogInJsonStringFormat)
          then new NoAnsiColorJsonString(logPrefix, logSuffix, LambdaEnvironment.originalOut)
          else new NoAnsiColorsSingleLine(logPrefix, logSuffix, LambdaEnvironment.originalOut)
        } else NoOpPrinter.out

      extension (
          v: PrintStream | NoAnsiColorJsonArray | NoAnsiColorsSingleLine | NoAnsiColorJsonString
      )
        inline def out: PrintStream = v match {
          case out: PrintStream           => out
          case ps: NoAnsiColorJsonArray   => ps.out
          case ps: NoAnsiColorJsonString  => ps.out
          case ps: NoAnsiColorsSingleLine => ps.out
        }
        def close(): Unit = v match {
          case out: PrintStream           => ()
          case ps: NoAnsiColorJsonArray   => ps.close()
          case ps: NoAnsiColorJsonString  => ps.close()
          case ps: NoAnsiColorsSingleLine => ps.close()
        }

      val previousSystemOut = System.out
      var isError = false

      val output =
        try {
          System.setOut(logPrinter.out)
          System.setErr(logPrinter.out)
          Console.withOut(logPrinter.out) {
            Console.withErr(logPrinter.out) {
              try {
                handleRequest(input)(using context).trim()
              } catch {
                case NonFatal(e) =>
                  isError = true
                  error(e.toString())
                  val stackTrace = e
                    .getStackTrace()
                    .filterNot { s =>
                      val n = s.getClassName()
                      n.startsWith("scala") || n.startsWith("java")
                    }
                  if (stackTrace.size > 30) then
                    stackTrace.take(15).foreach(println)
                    println("...")
                    stackTrace.takeRight(15).foreach(println)
                  else stackTrace.foreach(println)
                  createErrorMessage(e)
              }
            }
          }
        } finally {
          logPrinter.close()
          System.setOut(previousSystemOut)
          System.setErr(previousSystemOut)
        }

      val isJsonReponse =
        (output.startsWith("{") && output.endsWith("}"))
          || (output.startsWith("[") && output.endsWith("]"))

      val t1 = System.currentTimeMillis()

      // https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch_Embedded_Metric_Format_Specification.html
      val awsEmbededMetric =
        s""""_aws":{"Timestamp":$t1,"CloudWatchMetrics":[{"Namespace":"lambda-${functionName}-metrics","Dimensions":[${
            if (tagOpt.isDefined) then "[\"handler\"]" else "[]"
          }],"Metrics":[${LambdaRuntime.durationMetric}]}]}"""

      debug(
        if (lambdaEnvironment.shouldLogStructuredJson)
        then
          s"""{$awsEmbededMetric,"log":"RESPONSE",$structuredLogIntro,${
              if (lambdaEnvironment.shouldLogResponseIncludeRequest)
              then
                s""""request":${if (isJsonRequest) then event.body else s"\"${event.body.replace("\"", "\\\"")}\""},"""
              else ""
            }${
              if (lambdaInvocationDebugMode) then
                s""""response":${if (isJsonReponse) then output else s"\"${output.replace("\"", "\\\"")}\""},"""
              else ""
            }$structuredLogEnd,"timestamp":"${t1}","datetime":"${ZonedDateTime
              .now(ZoneUTC)
              .toString()}","duration":"${t1 - t0}","maxMemory":${Runtime
              .getRuntime()
              .maxMemory()},"totalMemory":${Runtime
              .getRuntime()
              .totalMemory()},"freeMemory":${Runtime.getRuntime().freeMemory()}}"""
        else s"[$id]$tag ${RESPONSE}LAMBDA RESPONSE [${t1 - t0}ms] ${AnsiColor.BOLD}$output"
      )

      val responseResult =
        if (isError)
        then reportError(output, lambdaEnvironment.errorUrl(requestId))
        else
          httpClient
            .send(lambdaEnvironment.responseRequest(requestId, output), BodyHandlers.ofString())

      val success = responseResult.statusCode() >= 200 && responseResult.statusCode() < 300
      if (!success) {
        throw new Exception(
          s"[$id]$tag Response rejected with status code ${responseResult.statusCode()}: ${responseResult.body}"
        )
      }

    } catch {
      case NonFatal(e) =>
        val errorMessage = createErrorMessage(e)
        error(errorMessage)
        try {
          reportError(errorMessage, lambdaEnvironment.errorUrl(requestId))
        } catch {
          case e => error(createErrorMessage(e))
        }
    }
  }

  /** Helper method to report error back to the AWS lambda host. */
  private final inline def reportError(
      errorMessage: String,
      errorUrl: URI
  )(using lambdaEnvironment: LambdaEnvironment): HttpResponse[String] =
    httpClient
      .send(
        HttpRequest
          .newBuilder(errorUrl)
          .POST(BodyPublishers.ofString(errorMessage))
          .setHeader("Lambda-Runtime-Function-Error-Type", "Runtime.UnknownReason")
          .build(),
        BodyHandlers.ofString()
      )

  private final def createErrorMessage(e: Throwable): String =
    val stackTrace = e.getStackTrace().filterNot { s =>
      val n = s.getClassName()
      n.startsWith("scala") || n.startsWith("java")
    }
    s"{\"success\":false,\"errorMessage\":\"${e.getMessage()}\", \"error\":\"${e
        .getClass()
        .getName()}\", \"stackTrace\": [${(stackTrace.take(3).map(_.toString()) ++ Array("...") ++ stackTrace
        .takeRight(3)
        .map(_.toString())).map(s => s"\"$s\"").mkString(",")}]}"

  // Lambda invocation for unit test purposes, does not interact with the AWS host, returns result directly. */
  final def test(
      input: String,
      overrides: Map[String, String] = Map.empty
  ): String =
    try {

      given lambdaEnvironment: LambdaEnvironment =
        new LambdaEnvironment(
          Map(
            "AWS_LAMBDA_RUNTIME_API" -> "none",
            "AWS_LAMBDA_FUNCTION_NAME" -> "test",
            "AWS_LAMBDA_FUNCTION_MEMORY_SIZE" -> "128",
            "AWS_LAMBDA_FUNCTION_VERSION" -> "0",
            "AWS_LAMBDA_LOG_GROUP_NAME" -> "none",
            "AWS_LAMBDA_LOG_STREAM_NAME" -> "none",
            "LAMBDA_RUNTIME_DEBUG_MODE" -> "ON",
            "LAMBDA_RUNTIME_TRACE_MODE" -> "ON"
          )
            ++ overrides
        )

      testLambdaEnvironment.setRelease(Some(lambdaEnvironment))

      val requestId = UUID.randomUUID().toString()

      val tag: String = getEventHandlerTag(input).map(t => s"[$t]").getOrElse("")

      debug(
        s"$tag ${REQUEST}LAMBDA REQUEST ${AnsiColor.BOLD}${input}"
      )

      val context = LambdaContext(requestId, Map.empty, lambdaEnvironment, switchOffDebugMode)

      val output = handleRequest(input)(using context)

      debug(
        s"$tag ${RESPONSE}LAMBDA RESPONSE ${AnsiColor.BOLD}$output"
      )

      output
    } catch {
      case NonFatal(e) =>
        createErrorMessage(e)
    }
}

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

  private def getRuntimeApi(): String =
    getProperty("AWS_LAMBDA_RUNTIME_API")

  final def getFunctionName(): String =
    getProperty("AWS_LAMBDA_FUNCTION_NAME")

  final def getFunctionVersion(): String =
    getProperty("AWS_LAMBDA_FUNCTION_VERSION")

  final def getMemoryLimitInMB(): Int =
    getProperty("AWS_LAMBDA_FUNCTION_MEMORY_SIZE").toInt

  final def getLogGroupName(): String =
    getProperty("AWS_LAMBDA_LOG_GROUP_NAME")

  final def getLogStreamName(): String =
    getProperty("AWS_LAMBDA_LOG_STREAM_NAME")

  final val isDebugMode: Boolean =
    maybeGetProperty("LAMBDA_RUNTIME_DEBUG_MODE")
      .map(parseBooleanFlagDefaultOff)
      .getOrElse(false)

  final val isTraceMode: Boolean =
    maybeGetProperty("LAMBDA_RUNTIME_TRACE_MODE")
      .map(parseBooleanFlagDefaultOff)
      .getOrElse(false)

  final val shouldDisplayAnsiColors: Boolean =
    maybeGetProperty("ANSI_COLORS_MODE")
      .map(parseBooleanFlagDefaultOn)
      .getOrElse(maybeGetProperty("NO_COLOR").forall(p => p.trim() != "1"))

  final val shouldLogStructuredJson: Boolean =
    !shouldDisplayAnsiColors &&
      maybeGetProperty("LAMBDA_RUNTIME_LOG_TYPE")
        .map(_.toUpperCase().contains("STRUCTURED"))
        .getOrElse(true)

  final val shouldLogInJsonArrayFormat: Boolean =
    maybeGetProperty("LAMBDA_RUNTIME_LOG_FORMAT")
      .map(_.toUpperCase().contains("JSON_ARRAY"))
      .getOrElse(false)

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
    s.toLowerCase() match {
      case "true" => true
      case "on"   => true
      case _      => false
    }

  final inline def parseBooleanFlagDefaultOn: String => Boolean = s =>
    s.toLowerCase() match {
      case "false" => false
      case "off"   => false
      case _       => true
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
              s"${AnsiColor.BLUE}[${lambdaEnvironment
                  .getFunctionName()}] $message${AnsiColor.RESET}",
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
              s"${AnsiColor.BLUE}[${lambdaEnvironment
                  .getFunctionName()}] $message${AnsiColor.RESET}",
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
            s"${AnsiColor.RED_B}${AnsiColor.WHITE}[${lambdaEnvironment
                .getFunctionName()}][ERROR] $message${AnsiColor.RESET}",
            LogLevel.ERROR
          )
  }
}

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
            s"${AnsiColor.BLUE}[${lambdaContext.getFunctionName()}] $message${AnsiColor.RESET}",
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
            s"${AnsiColor.BLUE}[${lambdaContext.getFunctionName()}] $message${AnsiColor.RESET}",
            LogLevel.TRACE
          )
      else ()

    inline def error(
        message: String
    )(using lambdaContext: LambdaContext): Unit =
      lambdaContext
        .getLogger()
        .log(
          s"${AnsiColor.RED_B}${AnsiColor.WHITE}[${lambdaContext
              .getFunctionName()}][ERROR] $message${AnsiColor.RESET}",
          LogLevel.ERROR
        )
  }
}

object SystemOutLambdaLogger extends LambdaLogger {

  override inline def log(message: Array[Byte]): Unit = {
    try {
      System.out.write(message);
    } catch
      case e =>
        // NOTE: When actually running on AWS Lambda, an IOException would never happen
        e.printStackTrace();
  }

  override inline def log(message: String): Unit = {
    System.out.println(message)
    System.out.flush()
  }
}

object NoAnsiColors {

  inline def printNoAnsi(message: String, out: PrintStream): Unit = {
    var escape: Boolean = false
    message.foreach { c =>
      if (escape) then {
        escape = c != 'm'
      } else if (c == '\u001b')
      then { escape = true }
      else {
        if (!c.isControl)
        then out.print(c)
      }
    }
    out.flush()
  }

  def getPrintStream(originalOut: PrintStream): PrintStream =
    new PrintStream(originalOut) {
      override def print(c: Char): Unit =
        printNoAnsi(String.valueOf(c), originalOut)

      override def println(c: Char): Unit =
        printNoAnsi(String.valueOf(c), originalOut)
        originalOut.println()

      override def print(s: String): Unit =
        printNoAnsi(s, originalOut)

      override def println(s: String): Unit =
        printNoAnsi(s, originalOut)
        originalOut.println()

      override def print(o: Object): Unit =
        if (o != null) then {
          printNoAnsi(o.toString(), originalOut)
        }

      override def println(o: Object): Unit =
        if (o != null) then {
          printNoAnsi(o.toString(), originalOut)
          originalOut.println()
        }
    }
}

object NoOpPrinter {

  private val buffer = new java.io.ByteArrayOutputStream()

  val out: PrintStream =
    new PrintStream(buffer) {
      override def print(c: Char): Unit = ()
      override def println(c: Char): Unit = ()
      override def print(s: String): Unit = ()
      override def println(s: String): Unit = ()
      override def println(o: Object): Unit = ()
      override def print(o: Object): Unit = ()
      override def println(): Unit = ()
    }

  def close(): Unit = ()

}

class NoAnsiColorsSingleLine(prefix: String, suffix: String, originalOut: PrintStream) {

  private lazy val buffer = new java.io.ByteArrayOutputStream()
  private lazy val localOut = new PrintStream(buffer)

  inline def printNoAnsi(message: String, out: PrintStream): Unit = {
    var escape: Boolean = false
    message.foreach { c =>
      if (escape) then {
        escape = c != 'm'
      } else if (c == '\u001b')
      then { escape = true }
      else {
        if (!c.isControl)
        then out.print(c)
        else if (c == '\n' || c == '\r')
        then localOut.print(" ")
      }
    }
    out.flush()
  }

  private var initialized: Boolean = false

  lazy val out: PrintStream =
    new PrintStream(localOut) {
      override def print(c: Char): Unit =
        printNoAnsi(String.valueOf(c), localOut)

      override def println(c: Char): Unit =
        printNoAnsi(String.valueOf(c), localOut)

      override def print(s: String): Unit =
        if (initialized)
        then localOut.print(" ")
        else
          localOut.print(prefix)
          localOut.print(" ")
          initialized = true
        printNoAnsi(s, localOut)

      override def println(s: String): Unit =
        print(s)

      override def println(o: Object): Unit =
        if (o != null) then print(o.toString())

      override def print(o: Object): Unit =
        if (o != null) then print(o.toString())

      override def println(): Unit = ()
    }

  def close(): Unit =
    localOut.println(suffix)
    originalOut.write(buffer.toByteArray())
    originalOut.flush()
    localOut.close()

}

class NoAnsiColorJsonArray(prefix: String, suffix: String, originalOut: PrintStream) {

  val MAX_LOG_ENTRY_SIZE = 250000

  private lazy val buffer = new java.io.ByteArrayOutputStream()
  private lazy val localOut = new PrintStream(buffer)

  private val beginning = System.nanoTime()

  inline def printNoAnsiJsonEscaped(message: String, out: PrintStream): Unit = {
    var escape: Boolean = false
    message.foreach { c =>
      if (escape) then {
        escape = c != 'm'
      } else if (c == '\u001b')
      then { escape = true }
      else {
        if (!c.isControl)
        then {
          if (c == '\\') then out.print("\\\\")
          else if (c == '"') then out.print("\\\"")
          else out.print(c)
        } else {
          escapeUnicode(c, out)
        }
      }
    }
    out.flush()
  }

  inline def toHex(nibble: Int): Char =
    (nibble + (if (nibble >= 10) 87 else 48)).toChar

  inline def escapeUnicode(c: Char, out: PrintStream): Unit =
    if (c == '\n') then out.print("\\n")
    else if (c == '\r') then out.print("\\r")
    else if (c == '\t') then out.print("\\t")
    else if (c == '\f') then out.print("\\f")
    else if (c == '\b') then out.print("\\b")
    else {
      out.print("\\\\u")
      out.print(toHex((c >> 12) & 15).toChar)
      out.print(toHex((c >> 8) & 15).toChar)
      out.print(toHex((c >> 4) & 15).toChar)
      out.print(toHex(c & 15).toChar)
    }

  inline def printTime(out: PrintStream): Unit =
    val time = System.nanoTime() - beginning
    out.print(f"+${time / 1000000}%06d: ")

  private var initialized: Boolean = false
  private var isNewLine: Boolean = false

  inline def remainingLogEntrySize: Int =
    MAX_LOG_ENTRY_SIZE - buffer.size()

  inline def checkBufferSize() = {
    if (remainingLogEntrySize <= 0)
    then {
      closeLogEntry()
      initialized = false
      isNewLine = false
      buffer.reset()
    }
  }

  lazy val out: PrintStream =
    new PrintStream(localOut) {

      override inline def print(c: Char): Unit =
        print(String.valueOf(c))
        checkBufferSize()

      override inline def println(c: Char): Unit =
        println(String.valueOf(c))
        checkBufferSize()

      override def print(s: String): Unit =
        checkBufferSize()
        if (s.length() > remainingLogEntrySize)
        then {
          val split = remainingLogEntrySize
          print(s.take(split))
          print(s.drop(split))
        } else if (!s.isBlank()) {
          if (!initialized) then
            localOut.print(prefix)
            localOut.print("\"logs\":[\"")
            printTime(localOut)
            initialized = true
          else if (isNewLine)
            localOut.print(", \"")
            printTime(localOut)
          printNoAnsiJsonEscaped(s, localOut)
          isNewLine = false
        }
        checkBufferSize()

      override def println(s: String): Unit =
        checkBufferSize()
        if (s.length() > remainingLogEntrySize)
        then {
          val split = remainingLogEntrySize
          print(s.take(split))
          println(s.drop(split))
        } else if (!s.isBlank()) {
          if (!initialized) then
            localOut.print(prefix)
            localOut.print("\"logs\":[\"")
            printTime(localOut)
            initialized = true
          else if (isNewLine)
            localOut.print(", \"")
            printTime(localOut)
          printNoAnsiJsonEscaped(s, localOut)
          localOut.print("\"")
          isNewLine = true
        } else {
          if (!isNewLine)
            localOut.print("\"")
            isNewLine = true
        }
        checkBufferSize()

      override inline def println(o: Object): Unit =
        if (o != null) then println(o.toString())

      override inline def print(o: Object): Unit =
        if (o != null) then print(o.toString())

      override inline def println(): Unit =
        if (!isNewLine) then
          localOut.print("\"")
          isNewLine = true
    }

  inline def closeLogEntry(): Unit =
    if (initialized) then {
      if (!isNewLine) then localOut.print("\"")
      localOut.print("]")
      localOut.println(suffix)
      localOut.flush()
      originalOut.write(buffer.toByteArray())
      originalOut.flush()
    }

  inline def close(): Unit =
    closeLogEntry()
    try { localOut.close() }
    catch { case _ => }

}

class NoAnsiColorJsonString(prefix: String, suffix: String, originalOut: PrintStream) {

  private lazy val buffer = new java.io.ByteArrayOutputStream()
  private lazy val localOut = new PrintStream(buffer)

  inline def printNoAnsiJsonEscaped(message: String, out: PrintStream): Unit = {
    var escape: Boolean = false
    message.foreach { c =>
      if (escape) then {
        escape = c != 'm'
      } else if (c == '\u001b')
      then { escape = true }
      else {
        if (!c.isControl)
        then {
          if (c == '\\') then out.print("\\\\")
          else if (c == '"') then out.print("\\\"")
          else out.print(c)
        } else {
          escapeUnicode(c, out)
        }
      }
    }
    out.flush()
  }

  inline def toHex(nibble: Int): Char =
    (nibble + (if (nibble >= 10) 87 else 48)).toChar

  inline def escapeUnicode(c: Char, out: PrintStream): Unit =
    if (c == '\n') then out.print("\\n")
    else if (c == '\r') then out.print("\\r")
    else if (c == '\t') then out.print("\\t")
    else if (c == '\f') then out.print("\\f")
    else if (c == '\b') then out.print("\\b")
    else {
      out.print("\\\\u")
      out.print(toHex((c >> 12) & 15).toChar)
      out.print(toHex((c >> 8) & 15).toChar)
      out.print(toHex((c >> 4) & 15).toChar)
      out.print(toHex(c & 15).toChar)
    }

  private var initialized: Boolean = false

  lazy val out: PrintStream =
    new PrintStream(localOut) {

      override def print(c: Char): Unit =
        printNoAnsiJsonEscaped(String.valueOf(c), localOut)

      override def println(c: Char): Unit =
        printNoAnsiJsonEscaped(String.valueOf(c), localOut)

      override def print(s: String): Unit =
        if (!s.isBlank()) {
          if (!initialized) then {
            localOut.print(prefix)
            localOut.print("\"logs\":\"")
            initialized = true
          }
          printNoAnsiJsonEscaped(s, localOut)
        }

      override def println(s: String): Unit =
        print(s)
        localOut.print("\\n")

      override def println(o: Object): Unit =
        if (o != null) then {
          print(o.toString())
          localOut.print("\\n")
        }

      override def print(o: Object): Unit =
        if (o != null) then print(o.toString())

      override def println(): Unit =
        localOut.print("\\n")
    }

  def close(): Unit =
    if (buffer.size() > 0) then {
      localOut.print("\"}")
      localOut.println(suffix)
      localOut.flush()
      originalOut.write(buffer.toByteArray())
      originalOut.flush()
    }
    try { localOut.close() }
    catch { case _ => }

}

object LambdaRuntime {

  object AnsiColor {
    inline val REQUEST = "\u001b[38;5;205m"
    inline val RESPONSE = "\u001b[38;5;214m"
  }

  inline def durationMetric = """{"Name":"duration","Unit":"Milliseconds","StorageResolution":60}"""
}

trait EventHandlerTag {

  /** Event tag will printed in the beginning of the log. Override to mark each log with event-specific tag. Default to
    * None.
    */
  def getEventHandlerTag(event: String): Option[String] = None
}

trait EventHandler {

  /** Abstract lambda invocation handler method. Provide your business logic here.
    */
  def handleRequest(input: String)(using LambdaContext): String
}
