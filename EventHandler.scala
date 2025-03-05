package org.encalmo.lambda

trait EventHandler {

  /** Provide your lambda business logic here.
    */
  def handleRequest(input: String)(using LambdaContext): String
}
