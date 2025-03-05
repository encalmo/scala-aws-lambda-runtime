package org.encalmo.lambda

object NoContext

trait EventHandler {

  /** Custom context initializez by the application. */
  type ApplicationContext = NoContext.type

  /** Initialize your implicit ApplicationContext here based on the lambda environment.
    *
    * This context can be anything you want to initialize ONCE per lambda run, e.g. AWS client, etc.
    */
  def initialize(using LambdaEnvironment): Unit | ApplicationContext = ()

  /** Provide your lambda business logic here.
    *
    * @param input
    *   event sent to the lambda
    * @return
    *   lambda output string
    */
  def handleRequest(input: String)(using LambdaContext, ApplicationContext): String
}
