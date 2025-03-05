package org.encalmo.lambda

trait EventHandler {

  /** Abstract lambda invocation handler method. Provide your business logic here.
    */
  def handleRequest(input: String)(using LambdaContext): String
}
