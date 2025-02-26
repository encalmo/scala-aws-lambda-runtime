package org.encalmo.lambda

import com.sun.net.httpserver.{HttpContext, HttpExchange, HttpHandler, HttpServer}
import munit.Assertions
import upickle.default.*

import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import scala.concurrent.ExecutionContext.Implicits.given
import scala.concurrent.{Future, Promise}
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

case class LambdaError(
    success: Boolean,
    errorMessage: String,
    error: String
) derives ReadWriter

class LambdaServiceFixture extends munit.Fixture[LambdaService]("lambdaService") {

  private var server: HttpServer = null
  private var lambdaService: LambdaService = null

  override def apply(): LambdaService =
    if (lambdaService != null)
    then lambdaService
    else throw new Exception("Test http server not initialized!")

  final override def beforeAll(): Unit = {
    this.server = HttpServer.create(new InetSocketAddress(0), 0)
    val address = s"localhost:${server.getAddress().getPort()}"
    server.start()
    lambdaService = new LambdaService(server)
    println(
      s"Started test http server at port ${server.getAddress().getPort()}"
    )
    System.setProperty("AWS_LAMBDA_RUNTIME_API", address)
    System.setProperty("AWS_LAMBDA_FUNCTION_NAME", "TestFunction")
    System.setProperty("AWS_LAMBDA_FUNCTION_VERSION", "$LATEST")
    System.setProperty("AWS_LAMBDA_FUNCTION_MEMORY_SIZE", "128")
    System.setProperty(
      "AWS_LAMBDA_LOG_GROUP_NAME",
      "/aws/lambda/TestFunction"
    )
    System.setProperty(
      "AWS_LAMBDA_LOG_STREAM_NAME",
      "/aws/lambda/TestFunction/$LATEST"
    )
  }

  final def close(): Unit = {
    server.stop(0)
    println(
      s"Shutdown test http server at port ${server.getAddress().getPort()}"
    )
  }

}

class LambdaService(server: HttpServer) extends Assertions {

  private val events =
    new LinkedBlockingQueue[(String, String)]

  server.createContext(
    "/2018-06-01/runtime/invocation/next",
    (exchange: HttpExchange) => {
      val (requestId, input) = events.take()

      val responseHeaders = exchange.getResponseHeaders()
      responseHeaders.put(
        "Lambda-Runtime-Aws-Request-Id",
        Seq(requestId).asJava
      )
      responseHeaders.put(
        "Lambda-Runtime-Deadline-Ms",
        Seq("30000").asJava
      )
      responseHeaders.put(
        "Lambda-Runtime-Invoked-Function-Arn",
        Seq(
          "arn:aws:lambda:us-east-1:00000000:function:TestFunction"
        ).asJava
      )
      responseHeaders.put(
        "Lambda-Runtime-Trace-Id",
        Seq(
          "Root=1-5bef4de7-ad49b0e87f6ef6c87fc2e700;Parent=9a9197af755a6419;Sampled=1"
        ).asJava
      )
      exchange.sendResponseHeaders(200, input.length())
      val os = exchange.getResponseBody()
      os.write(input.getBytes())
      os.close()
    }
  )

  server.createContext(
    "/2018-06-01/runtime/init/error",
    (exchange: HttpExchange) => {
      println(s"[LambdaService] Initialization error: ${Source
          .fromInputStream(exchange.getRequestBody())
          .mkString}")
      exchange.sendResponseHeaders(202, -1)
    }
  )

  def mockAndAssertLambdaInvocation(
      input: String,
      expectedOutput: String
  ): Future[String] = {
    // println(s"[LambdaService] Expecting: $input => $expectedOutput")
    val requestId = UUID.randomUUID().toString()
    events.offer((requestId, input))
    mockRuntimeInvocationResponse(requestId)
      .andThen {
        case Success(result) =>
          assertEquals(result, expectedOutput)
          println(
            // s"[LambdaService] Success confirmed: $result == $expectedOutput"
          )
        case Failure(exception) =>
          fail(
            "[LambdaService] Invocation didn't return any result",
            exception
          )
      }
  }

  def mockAndAssertLambdaInvocationError[I: ReadWriter](
      input: I,
      expectedError: LambdaError
  ): Future[LambdaError] = {
    println(s"[LambdaService] Expecting failure: $input => $expectedError")
    val requestId = UUID.randomUUID().toString()
    val event = ujson.write(writeJs(input))
    events.offer((requestId, event))
    mockRuntimeInvocationError(requestId)
      .andThen {
        case Success(error) =>
          assertEquals(error.errorMessage, expectedError.errorMessage)
          assertEquals(error.error, expectedError.error)
          println(
            s"[LambdaService] Error confirmed: ${error.errorMessage} == ${expectedError.errorMessage}"
          )
        case Failure(exception) =>
          fail(
            "[LambdaService] Invocation didn't return any error",
            exception
          )
      }
  }

  def mockRuntimeInvocationResponse(
      requestId: String
  ): Future[String] = {
    var httpContext: HttpContext = null
    var errorHttpContext: HttpContext = null
    val promise = Promise[String]

    httpContext = server.createContext(
      s"/2018-06-01/runtime/invocation/$requestId/response",
      (exchange: HttpExchange) => {
        promise.complete(Try {
          val body = Source
            .fromInputStream(exchange.getRequestBody())
            .mkString
          exchange
            .sendResponseHeaders(202, -1)
          server.removeContext(httpContext)
          server.removeContext(errorHttpContext)
          body
        })
      }
    )
    errorHttpContext = server.createContext(
      s"/2018-06-01/runtime/invocation/$requestId/error",
      (exchange: HttpExchange) => {
        val body = Source
          .fromInputStream(exchange.getRequestBody())
          .mkString
        exchange.sendResponseHeaders(202, -1)
        server.removeContext(httpContext)
        server.removeContext(errorHttpContext)
        promise.complete(
          Failure(
            new Exception(
              s"Expected success but got an error:\n$body"
            )
          )
        )
      }
    )
    promise.future
  }

  def mockRuntimeInvocationError(
      requestId: String
  ): Future[LambdaError] = {
    var httpContext: HttpContext = null
    var errorHttpContext: HttpContext = null
    val promise = Promise[LambdaError]
    httpContext = server.createContext(
      s"/2018-06-01/runtime/invocation/$requestId/response",
      (exchange: HttpExchange) => {
        val body = Source
          .fromInputStream(exchange.getRequestBody())
          .mkString
        exchange.sendResponseHeaders(202, -1)
        server.removeContext(httpContext)
        server.removeContext(errorHttpContext)
        promise.complete(
          Failure(
            new Exception(
              s"Expected an error but got successful result:\n$body"
            )
          )
        )
      }
    )

    errorHttpContext = server.createContext(
      s"/2018-06-01/runtime/invocation/$requestId/error",
      (exchange: HttpExchange) => {
        promise.complete(Try {
          val body = Source
            .fromInputStream(exchange.getRequestBody())
            .mkString
          exchange.sendResponseHeaders(202, -1)
          server.removeContext(httpContext)
          server.removeContext(errorHttpContext)
          upickle.default.read[LambdaError](body)
        })
      }
    )
    promise.future
  }
}
