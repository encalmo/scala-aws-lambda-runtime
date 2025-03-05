package org.encalmo.lambda

trait EventHandlerTag {

  /** Event tag will printed in the beginning of the log. Override to mark each log with event-specific tag. Default to
    * None.
    */
  def getEventHandlerTag(event: String): Option[String] = None
}
