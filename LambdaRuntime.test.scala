package org.encalmo.lambda

import com.amazonaws.services.lambda.runtime.ClientContext
import com.amazonaws.services.lambda.runtime.CognitoIdentity
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.LambdaLogger
import org.encalmo.utils.JsonUtils.*
import upickle.default.*

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.HexFormat
import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.AnsiColor

class LambdaRuntimeSpec extends munit.FunSuite {

  val lambdaService = new LambdaServiceFixture()
  override def munitFixtures = List(lambdaService)

  case class MyContext(foo: String)

  test("Lambda runtime test execution 1") {
    val lambdaRuntime = new LambdaRuntime {

      type ApplicationContext = MyContext

      override def initialize(using environment: LambdaEnvironment) = {
        environment.info(
          s"Initializing test echo lambda ${environment.getFunctionName()} ..."
        )
        MyContext(foo = "bar")
      }

      override def handleRequest(input: String)(using LambdaContext, MyContext): String =
        input.reverse
    }

    assertEquals(
      lambdaRuntime.test("Hello!"),
      "!olleH"
    )
  }

  test("Lambda runtime test execution 2") {
    val lambdaRuntime = new LambdaRuntime {

      type ApplicationContext = Unit

      override def initialize(using environment: LambdaEnvironment) = {
        environment.info(
          s"Initializing lambda ${environment.getFunctionName()} ..."
        )
      }

      override def handleRequest(input: String)(using
          LambdaContext,
          ApplicationContext
      ): String =
        ApiGatewayResponse(
          body = input.reverse,
          statusCode = 200,
          headers = Map.empty,
          isBase64Encoded = false
        ).writeAsString
    }

    assertEquals(
      lambdaRuntime.test("Hello!"),
      """{"body":"!olleH","statusCode":200,"headers":{},"isBase64Encoded":false}"""
    )
  }

  case class ApiGatewayRequest(body: String) derives ReadWriter

  test("Lambda runtime test execution 3") {
    val lambdaRuntime =
      new LambdaRuntime {

        type ApplicationContext = Unit

        override def initialize(using environment: LambdaEnvironment) = {
          environment.info(
            s"Initializing lambda ${environment.getFunctionName()} ..."
          )
        }

        override def handleRequest(input: String)(using
            LambdaContext,
            ApplicationContext
        ): String =
          ApiGatewayResponse(
            body = input.readAs[ApiGatewayRequest].body.reverse,
            statusCode = 200,
            headers = Map.empty,
            isBase64Encoded = false
          ).writeAsString
      }

    assertEquals(
      {
        val event = upickle.default.write(ApiGatewayRequest("Hello!-X"))
        lambdaRuntime.test(event)
      },
      """{"body":"X-!olleH","statusCode":200,"headers":{},"isBase64Encoded":false}"""
    )
  }

  test("Lambda runtime test execution using java handler interface") {
    val lambdaRuntime = new LambdaRuntime {

      type ApplicationContext = MyContext

      override def initialize(using environment: LambdaEnvironment) = {
        environment.info(
          s"Initializing test echo lambda ${environment.getFunctionName()} ..."
        )
        MyContext(foo = "bar")
      }

      override def handleRequest(input: String)(using LambdaContext, MyContext): String =
        input.reverse
    }

    val outputStream = new ByteArrayOutputStream()

    lambdaRuntime.handleRequest(
      new ByteArrayInputStream("Hello!".getBytes(StandardCharsets.UTF_8)),
      outputStream,
      new Context {

        override def getAwsRequestId(): String = "foo-123"

        override def getRemainingTimeInMillis(): Int = ???

        override def getClientContext(): ClientContext = ???

        override def getIdentity(): CognitoIdentity = ???

        override def getFunctionName(): String = ???

        override def getLogStreamName(): String = ???

        override def getFunctionVersion(): String = ???

        override def getLogger(): LambdaLogger = ???

        override def getInvokedFunctionArn(): String = ???

        override def getLogGroupName(): String = ???

        override def getMemoryLimitInMB(): Int = ???

      }
    )

    assertEquals(outputStream.toString(StandardCharsets.UTF_8), "!olleH")
  }

  test("Lambda runtime execution when lambda is failing") {
    val lambdaRuntime = new SimpleLambdaRuntime {

      override def initialize(using environment: LambdaEnvironment) = {
        environment.info(
          s"Initializing lambda ${environment.getFunctionName()} ..."
        )
      }

      override def handleRequest(input: String)(using LambdaContext, ApplicationContext): String =
        println("About to throw an exception from inside lambda ...")
        throw new Exception("Foo")
    }
      .initializeLambdaRuntime(
        Map("LAMBDA_RUNTIME_DEBUG_MODE" -> "ON", "ANSI_COLORS_MODE" -> "OFF")
      )
      .start()

    lambdaService()
      .mockAndAssertLambdaInvocationError(
        s"Hello lambda!\n Foo",
        LambdaError(false, "Foo", "java.lang.Exception")
      )
      .andThen { case _ => lambdaRuntime.shutdown() }

  }

  test("Lambda runtime hosted execution") {
    val lambdaRuntime = new SimpleLambdaRuntime {

      override def handleRequest(input: String)(using LambdaContext, ApplicationContext): String =
        input.reverse
    }
      .initializeLambdaRuntime(
        Map("LAMBDA_RUNTIME_DEBUG_MODE" -> "ON", "ANSI_COLORS_MODE" -> "ON")
      )
      .start()

    (0 to 100).foreach { n =>
      lambdaService().mockAndAssertLambdaInvocation(
        s"$n-Hello!\nFoo",
        s"ooF\n!olleH-${n.toString().reverse}"
      )
      lambdaService().mockAndAssertLambdaInvocation(
        s"$n-Abba",
        s"abbA-${n.toString().reverse}"
      )
    }

    lambdaService()
      .mockAndAssertLambdaInvocation(
        "{\"foo\": \"bar\"}",
        "}\"rab\" :\"oof\"{"
      )
      .andThen { case _ => lambdaRuntime.shutdown() }
  }

  test("Execute TestEchoLambda with colors".ignore) {
    val lambdaRuntime = new TestEchoLambda()
      .initializeLambdaRuntime(
        Map("LAMBDA_RUNTIME_DEBUG_MODE" -> "ON")
      )
      .start()
    lambdaService()
      .mockAndAssertLambdaInvocation(
        "Hello Darling!",
        "Hello Darling!"
      )
      .andThen { case _ => lambdaRuntime.shutdown() }
  }

  test(
    "Execute TestEchoLambda without colors and with json array log format".ignore
  ) {
    val lambdaRuntime = new TestEchoLambda()
      .initializeLambdaRuntime(
        Map("LAMBDA_RUNTIME_DEBUG_MODE" -> "ON", "NO_COLOR" -> "1", "LAMBDA_RUNTIME_LOG_FORMAT" -> "JSON_ARRAY")
      )
      .start()
    lambdaService()
      .mockAndAssertLambdaInvocation(
        "Hello Darling!",
        "Hello Darling!"
      )
      .andThen { case _ => lambdaRuntime.shutdown() }
  }

  test(
    "Execute TestEchoLambda without colors and with structured json array log format".ignore
  ) {
    val lambdaRuntime = new TestEchoLambda()
      .initializeLambdaRuntime(
        Map(
          "LAMBDA_RUNTIME_DEBUG_MODE" -> "ON",
          "NO_COLOR" -> "1",
          "LAMBDA_RUNTIME_LOG_FORMAT" -> "JSON_ARRAY",
          "LAMBDA_RUNTIME_LOG_TYPE" -> "STRUCTURED"
        )
      )
      .start()
    lambdaService()
      .mockAndAssertLambdaInvocation(
        "Hello Darling!",
        "Hello Darling!"
      )
      .andThen { case _ => lambdaRuntime.shutdown() }
  }

  test(
    "Execute TestEchoLambda without colors and with structured json array log format, very large log".ignore
  ) {
    val lambdaRuntime = new TestEchoLambda(Some("Hello! " * 100000))
      .initializeLambdaRuntime(
        Map(
          "LAMBDA_RUNTIME_DEBUG_MODE" -> "ON",
          "NO_COLOR" -> "1",
          "LAMBDA_RUNTIME_LOG_FORMAT" -> "JSON_ARRAY",
          "LAMBDA_RUNTIME_LOG_TYPE" -> "STRUCTURED"
        )
      )
      .start()

    lambdaService()
      .mockAndAssertLambdaInvocation(
        "Hello Darling!",
        "Hello Darling!"
      )
      .andThen { case _ => lambdaRuntime.shutdown() }
  }

  test(
    "Execute TestEchoLambda without colors and with structured json array log format and json event".ignore
  ) {
    val lambdaRuntime = new TestEchoLambda()
      .initializeLambdaRuntime(
        Map(
          "LAMBDA_RUNTIME_DEBUG_MODE" -> "ON",
          "NO_COLOR" -> "1",
          "LAMBDA_RUNTIME_LOG_FORMAT" -> "JSON_ARRAY",
          "LAMBDA_RUNTIME_LOG_TYPE" -> "STRUCTURED"
        )
      )
      .start()
    lambdaService()
      .mockAndAssertLambdaInvocation(
        """{"foo":"bar","baz":1}""",
        """{"foo":"bar","baz":1}"""
      )
      .andThen { case _ => lambdaRuntime.shutdown() }
  }

  test(
    "Execute TestEchoLambda without colors and with json string log format".ignore
  ) {
    val lambdaRuntime = new TestEchoLambda()
      .initializeLambdaRuntime(
        Map(
          "LAMBDA_RUNTIME_DEBUG_MODE" -> "ON",
          "ANSI_COLORS_MODE" -> "OFF",
          "LAMBDA_RUNTIME_LOG_FORMAT" -> "JSON_STRING",
          "LAMBDA_RUNTIME_LOG_TYPE" -> "PLAIN"
        )
      )
      .start()
    lambdaService()
      .mockAndAssertLambdaInvocation(
        "Hello Darling!",
        "Hello Darling!"
      )
      .andThen { case _ => lambdaRuntime.shutdown() }
  }

  test(
    "Execute TestEchoLambda without colors and with structured json string log format".ignore
  ) {
    val lambdaRuntime = new TestEchoLambda()
      .initializeLambdaRuntime(
        Map(
          "LAMBDA_RUNTIME_DEBUG_MODE" -> "ON",
          "ANSI_COLORS_MODE" -> "OFF",
          "LAMBDA_RUNTIME_LOG_FORMAT" -> "JSON_STRING",
          "LAMBDA_RUNTIME_LOG_TYPE" -> "STRUCTURED"
        )
      )
      .start()
    lambdaService()
      .mockAndAssertLambdaInvocation(
        "Hello Darling!",
        "Hello Darling!"
      )
      .andThen { case _ => lambdaRuntime.shutdown() }
  }

  test(
    "Execute TestEchoLambda without colors and without json log format".ignore
  ) {
    val lambdaRuntime = new TestEchoLambda()
      .initializeLambdaRuntime(
        Map(
          "LAMBDA_RUNTIME_DEBUG_MODE" -> "ON",
          "ANSI_COLORS_MODE" -> "OFF",
          "LAMBDA_RUNTIME_LOG_FORMAT" -> "PLAIN"
        )
      )
      .start()
    lambdaService()
      .mockAndAssertLambdaInvocation(
        "Hello Darling!",
        "Hello Darling!"
      )
      .andThen { case _ => lambdaRuntime.shutdown() }
  }

  test("NoAnsiColors outputs no ansi colors") {
    import org.encalmo.lambda.AnsiColor.*
    import scala.io.AnsiColor.*
    val message =
      s"${PREFIX}Hello ${REQUEST}World!${RESET}\n${GREEN}How are you today?${RESET}"
    SystemOutLambdaLogger.log(message)
    NoAnsiColors.printNoAnsi(message, System.out)
  }

  test("json array print stream") {
    import org.encalmo.lambda.AnsiColor.*
    import scala.io.AnsiColor.*
    val message =
      s"${PREFIX}Hello ${REQUEST}\"World\"!${RESET}\n${GREEN}How are you today?${RESET}"
    val capture = CapturingPrintStream()
    val jsonArrayPrintStream = NoAnsiColorJsonArray("[test]", "[end]", capture.out)
    jsonArrayPrintStream.out.println(" " * 3)
    jsonArrayPrintStream.out.println("-" * 16)
    jsonArrayPrintStream.out.println(message)
    jsonArrayPrintStream.out.print(message)
    jsonArrayPrintStream.out.println(message)
    jsonArrayPrintStream.out.print(message)
    jsonArrayPrintStream.out.println(message)
    jsonArrayPrintStream.out.print(message)
    jsonArrayPrintStream.out.println(" " * 3)
    jsonArrayPrintStream.out.println("-" * 16)
    jsonArrayPrintStream.close()
    println(capture.asString)
  }

  override def afterAll(): Unit =
    lambdaService.close()

}

class CapturingPrintStream {

  val buf = new ByteArrayOutputStream()
  val out = new PrintStream(buf, true)

  def asString: String =
    new String(buf.toByteArray())

  def asHexString: String =
    HexFormat.ofDelimiter(" ").formatHex(buf.toByteArray())

  def asCharsString: String =
    buf.toByteArray().map(_.toChar).mkString(" ")

  def asIntsString: String =
    buf.toByteArray().map(Byte.byte2int).mkString(" ")

  def showResults: Unit =
    System.out.println(asString)
    System.out.println(asHexString)
    System.out.println(asCharsString)
    System.out.println(asIntsString)
}
