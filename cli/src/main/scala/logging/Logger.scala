package terraverse.logging

sealed abstract class LogLevel(val level: Int) {
  def <(other: LogLevel): Boolean  = level < other.level
  def <=(other: LogLevel): Boolean = level <= other.level
  def >(other: LogLevel): Boolean  = level > other.level
  def >=(other: LogLevel): Boolean = level >= other.level
}
object LogLevel                                {
  case object Trace extends LogLevel(0)
  case object Debug extends LogLevel(1)
  case object Info  extends LogLevel(2)
  case object Warn  extends LogLevel(3)
  case object Error extends LogLevel(4)

  def fromString(str: String): Either[String, LogLevel] = Some(str).collect {
    case "trace" => Trace
    case "debug" => Debug
    case "info"  => Info
    case "warn"  => Warn
    case "error" => Error
  }.toRight(s"Invalid log level: $str. Expected one of: debug, info, warn, error.")
}

object Logger {
  import typings.node.global.console

  var level: LogLevel = LogLevel.Trace

  def trace(s: Any): Unit = if (level <= LogLevel.Trace) console.log(s.toString)
  def debug(s: Any): Unit = if (level <= LogLevel.Debug) console.log(s.toString)
  def info(s: Any): Unit  = if (level <= LogLevel.Info) console.log(s.toString)
  def warn(s: Any): Unit  = if (level <= LogLevel.Warn) console.warn(s.toString)
  def error(s: Any): Unit = if (level <= LogLevel.Error) console.error(s.toString)
  def log(s: Any): Unit   = console.log(s.toString)
}
