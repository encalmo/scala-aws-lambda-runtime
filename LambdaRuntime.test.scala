package org.encalmo.lambda

import org.encalmo.lambda.Utils.*
import upickle.default.*

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.HexFormat
import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.AnsiColor

class LambdaRuntimeSpec extends munit.FunSuite {

  val lambdaService = new LambdaServiceFixture()
  override def munitFixtures = List(lambdaService)

  test("Lambda runtime test execution 1") {
    val lambdaRuntime = new LambdaRuntime {

      lazy val config = configure { (environment: LambdaEnvironment) =>
        println(s"Initializing 1st lambda ${environment.getFunctionName()} ...")
      }

      override def handleRequest(input: String)(using LambdaContext): String =
        input.reverse
    }

    assertEquals(
      lambdaRuntime.test("Hello!"),
      "!olleH"
    )
  }

  test("Lambda runtime test execution 2") {
    val lambdaRuntime = new LambdaRuntime {

      lazy val config = configure { (environment: LambdaEnvironment) =>
        println(s"Initializing 2nd lambda ${environment.getFunctionName()} ...")
      }

      override def handleRequest(input: String)(using
          LambdaContext
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

        lazy val config = configure { (environment: LambdaEnvironment) =>
          println(
            s"Initializing 3rd lambda ${environment.getFunctionName()} ..."
          )
        }

        override def handleRequest(input: String)(using
            LambdaContext
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

  test("Lambda runtime execution when lambda is failing") {
    val lambdaRuntime = new LambdaRuntime {
      override def handleRequest(input: String)(using LambdaContext): String =
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
    val lambdaRuntime = new LambdaRuntime {
      override def handleRequest(input: String)(using LambdaContext): String =
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

  test("Lambda runtime with secrets retrieval".ignore) {
    val response = new LambdaRuntime {
      override def handleRequest(input: String)(using
          lambdaContext: LambdaContext
      ): String =
        lambdaContext.maybeGetProperty("TWILIO_HOST").getOrElse("")
    }
      .test(
        "Hello!",
        Map(
          "LAMBDA_RUNTIME_DEBUG_MODE" -> "true",
          "ENVIRONMENT_SECRETS" -> "arn:aws:secretsmanager:us-east-1:846345969893:secret:Development/apigateway/external-integrations/beta/variables"
        )
      )
    assert(!response.isBlank(), "Expected non-empty secret value")
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
    import org.encalmo.lambda.LambdaRuntime.AnsiColor.*
    import scala.io.AnsiColor.*
    val message =
      s"${BLUE}Hello ${REQUEST}World!${RESET}\n${GREEN}How are you today?${RESET}"
    SystemOutLambdaLogger.log(message)
    NoAnsiColors.printNoAnsi(message, System.out)
  }

  test("json array print stream") {
    import org.encalmo.lambda.LambdaRuntime.AnsiColor.*
    import scala.io.AnsiColor.*
    val message =
      s"${BLUE}Hello ${REQUEST}\"World\"!${RESET}\n${GREEN}How are you today?${RESET}"
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

  test("Eventually".ignore) {
    var i = 0
    Eventually.maybe(
      if (i < 4) {
        i = i + 1
        throw new Exception()
      } else i
    )
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
