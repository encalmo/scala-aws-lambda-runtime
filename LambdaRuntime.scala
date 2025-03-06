package org.encalmo.lambda

import com.amazonaws.services.lambda.runtime.logging.LogLevel
import org.encalmo.lambda.AnsiColor.*

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
import java.util.function.BinaryOperator
import scala.io.AnsiColor
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.reflect.ClassTag
import scala.util.control.NonFatal

/** Simplified lambda runtime when no application context is required. */
trait SimpleLambdaRuntime extends LambdaRuntime {
  type ApplicationContext = Unit
  override def initialize(using LambdaEnvironment): Unit = ()
}

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

  /** Handle of the current test application context. */
  private val testApplicationContext: AtomicReference[Option[ApplicationContext]] =
    new AtomicReference(None)

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

    lazy val applicationContext: ApplicationContext

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

  /** Configure something with lambda environment provided. */
  private def configure[T](f: LambdaEnvironment ?=> T): T =
    instance.getAcquire() match {
      case Some(i) =>
        trace("Configuring lambda ...")(using i.lambdaEnvironment)
        f(using i.lambdaEnvironment)
      case None =>
        testLambdaEnvironment.getAcquire() match {
          case Some(le) =>
            trace("Configuring lambda using test environment ...")(using le)
            f(using le)
          case None =>
            throw new IllegalStateException(
              "Lambda instance is not initialized yet at this point. Use `lazy val config = configure(...)` declaration."
            )
        }
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

            val logPrefix =
              if (lambdaEnvironment.shouldLogStructuredJson)
              then s"""{"log":"INIT","lambda":"${lambdaEnvironment.getFunctionName()}","""
              else s"[${lambdaEnvironment.getFunctionName()}] LAMBDA INIT "

            val logSuffix =
              if (lambdaEnvironment.shouldLogStructuredJson)
              then s""","lambdaVersion":"${lambdaEnvironment.getFunctionVersion()}"}"""
              else ""

            lazy val applicationContext: ApplicationContext =
              LambdaRuntime
                .withLogCapture(
                  lambdaEnvironment,
                  logPrefix,
                  logSuffix,
                  true,
                  configure(initialize)
                )

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
                    invokeHandleRequest(id)(using lambdaEnvironment, applicationContext)
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
                          reportError(LambdaRuntime.createErrorMessage(e), lambdaEnvironment.initErrorUrl)
                        } catch {
                          case e => error(LambdaRuntime.createErrorMessage(e))
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
              trace(s"[$runtimeId] LambdaRuntime started.")
              this
            }

            final override def waitUntilInterrupted(): Instance = {
              trace(s"Waiting ...")
              try (loopThread.join())
              catch {
                case e: InterruptedException =>
                  trace(s"[$runtimeId] LambdaRuntime interrupted.")
                  instance.setRelease(None)
              }
              this
            }

            final override def pause(): Instance = {
              semaphore.acquire()
              trace(s"[$runtimeId] LambdaRuntime paused.")
              this
            }

            final override def shutdown(): Instance = {
              trace(s"[$runtimeId] LambdaRuntime shutdowns.")
              active.set(false)
              lambdaEnvironment.resetOut()
              Thread.sleep(100)
              loopThread.interrupt()
              this
            }

            trace(s"[$runtimeId] LambdaRuntime initialized.")
          }
          instance.setRelease(Some(i))
          i
        } catch {
          // Lambda runtime initilization has failed, report error to the AWS host
          case NonFatal(e) =>
            given le: LambdaEnvironment = new LambdaEnvironment()
            try {
              reportError(LambdaRuntime.createErrorMessage(e), le.initErrorUrl)
            } catch {
              case e => error(LambdaRuntime.createErrorMessage(e))
            }
            le.resetOut()
            throw e
        }
    }
  }

  /* ----------------------------------------------------------
   * THE ACTUAL LAMBDA BUSINESS METHOD INVOCATION HAPPENS HERE.
   * ---------------------------------------------------------- */
  private final def invokeHandleRequest(
      id: Int
  )(using lambdaEnvironment: LambdaEnvironment, applicationContext: ApplicationContext): Unit = {
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
      else {
        s"[$id]$tag ${REQUEST}LAMBDA REQUEST ${AnsiColor.BOLD}${event.body}"
      }
    )

    try {
      val lambdaContext = LambdaContext(
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

      val logPrefix =
        if (lambdaEnvironment.shouldLogStructuredJson)
        then s"""{"log":"LOGS",$structuredLogIntro,"""
        else s"[${lambdaEnvironment.getFunctionName()}] [$id]$tag LAMBDA LOGS {"

      val logSuffix =
        if (lambdaEnvironment.shouldLogStructuredJson)
        then s""",$structuredLogEnd}"""
        else "}"

      val result: Either[String, String] =
        LambdaRuntime
          .withLogCapture(
            lambdaEnvironment,
            logPrefix,
            logSuffix,
            lambdaInvocationDebugMode,
            try {
              Right(handleRequest(input)(using lambdaContext, applicationContext).trim())
            } catch {
              case NonFatal(e) =>
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
                Left(LambdaRuntime.createErrorMessage(e))
            }
          )

      val t1 = System.currentTimeMillis()

      val output = result.fold(identity, identity)

      val isJsonReponse =
        (output.startsWith("{") && output.endsWith("}"))
          || (output.startsWith("[") && output.endsWith("]"))

      // https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch_Embedded_Metric_Format_Specification.html
      val awsEmbededMetric =
        s""""_aws":{"Timestamp":$t1,"CloudWatchMetrics":[{"Namespace":"lambda-${functionName}-metrics","Dimensions":[${
            if (tagOpt.isDefined) then "[\"handler\"]" else "[]"
          }],"Metrics":[${LambdaRuntime.durationMetric}]}]}"""

      debug(
        if (lambdaEnvironment.shouldLogStructuredJson)
        then
          s"""{"log":"RESPONSE",$structuredLogIntro,${
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
              .totalMemory()},"freeMemory":${Runtime.getRuntime().freeMemory()},$awsEmbededMetric}"""
        else {
          s"[$id]$tag ${RESPONSE}LAMBDA RESPONSE [${t1 - t0}ms] ${AnsiColor.BOLD}$output"
        }
      )

      val responseResult =
        if (result.isLeft)
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
        val errorMessage = LambdaRuntime.createErrorMessage(e)
        error(errorMessage)
        try {
          reportError(errorMessage, lambdaEnvironment.errorUrl(requestId))
        } catch {
          case e => error(LambdaRuntime.createErrorMessage(e))
        }
    }
  }

  // Lambda invocation for unit test purposes, does not interact with the AWS host, returns result directly. */
  final def test(
      input: String,
      overrides: Map[String, String] = Map.empty
  ): String =
    try {

      val requestId = UUID.randomUUID().toString()

      val tag: String = getEventHandlerTag(input).map(t => s"[$t]").getOrElse("")

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

      val applicationContext: ApplicationContext =
        testApplicationContext
          .accumulateAndGet(
            None,
            new BinaryOperator[Option[ApplicationContext]] {
              override def apply(
                  existing: Option[ApplicationContext],
                  dummy: Option[ApplicationContext]
              ): Option[ApplicationContext] =
                existing.orElse {
                  Some(configure(initialize))
                }
            }
          )
          .get

      debug(
        s"$tag ${REQUEST}LAMBDA REQUEST ${AnsiColor.BOLD}${input}"
      )

      val lambdaContext =
        LambdaContext(requestId, Map.empty, lambdaEnvironment, switchOffDebugMode)

      val output = handleRequest(input)(using lambdaContext, applicationContext)

      debug(
        s"$tag ${RESPONSE}LAMBDA RESPONSE ${AnsiColor.BOLD}$output"
      )

      output
    } catch {
      case NonFatal(e) =>
        LambdaRuntime.createErrorMessage(e)
    }

    /** Report error back to the AWS lambda host. */
  final inline def reportError(
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
}

object LambdaRuntime {

  inline def durationMetric = """{"Name":"duration","Unit":"Milliseconds","StorageResolution":60}"""

  final def createErrorMessage(e: Throwable): String =
    val stackTrace = e.getStackTrace().filterNot { s =>
      val n = s.getClassName()
      n.startsWith("scala") || n.startsWith("java")
    }
    s"{\"success\":false,\"errorMessage\":\"${e.getMessage()}\", \"error\":\"${e
        .getClass()
        .getName()}\", \"stackTrace\": [${(stackTrace.take(3).map(_.toString()) ++ Array("...") ++ stackTrace
        .takeRight(3)
        .map(_.toString())).map(s => s"\"$s\"").mkString(",")}]}"

  final def withLogCapture[A](
      lambdaEnvironment: LambdaEnvironment,
      logPrefix: String,
      logSuffix: String,
      debugMode: Boolean,
      body: => A
  ): A = {

    val logPrinter: PrintStream | NoAnsiColorJsonArray | NoAnsiColorJsonString | NoAnsiColorsSingleLine =
      if (debugMode) then {
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

      inline def close(): Unit = v match {
        case out: PrintStream           => ()
        case ps: NoAnsiColorJsonArray   => ps.close()
        case ps: NoAnsiColorJsonString  => ps.close()
        case ps: NoAnsiColorsSingleLine => ps.close()
      }

    val previousSystemOut = System.out
    var isError = false

    try {
      System.setOut(logPrinter.out)
      System.setErr(logPrinter.out)
      Console.withOut(logPrinter.out) {
        Console.withErr(logPrinter.out) {
          body
        }
      }
    } finally {
      logPrinter.close()
      System.setOut(previousSystemOut)
      System.setErr(previousSystemOut)
    }
  }
}
