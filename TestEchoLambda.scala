package org.encalmo.lambda

import scala.annotation.static
import scala.io.AnsiColor

object TestEchoLambda {
  @static def main(args: Array[String]): Unit = new TestEchoLambda().run()
}

class TestEchoLambda(log: Option[String] = None) extends LambdaRuntime {

  override def initialize(using environment: LambdaEnvironment) = {
    environment.info(
      s"Initializing test echo lambda ${environment.getFunctionName()} ..."
    )
  }

  override inline def handleRequest(input: String)(using LambdaContext, ApplicationContext): String = {
    print(s"${AnsiColor.GREEN}Handling lambda request.")
    print(s" Input: $input")
    System.out.println(s"${AnsiColor.RESET}")
    println(log)
    System.out.println(
      s"${AnsiColor.GREEN}One more log line.${AnsiColor.RESET}"
    )
    println(s"${AnsiColor.GREEN}And one more log line.${AnsiColor.RESET}")
    input
  }

}
