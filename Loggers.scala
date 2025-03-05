package org.encalmo.lambda

import com.amazonaws.services.lambda.runtime.LambdaLogger

import java.io.PrintStream

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
