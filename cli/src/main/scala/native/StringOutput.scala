package talpini.native

import typings.colors.{safeMod => Colors}

import scala.scalajs.js
import scala.scalajs.js.JSStringOps

object StringOutput {
  private val colors = Array[String => String](
    Colors.red,
    Colors.green,
    Colors.yellow,
    Colors.blue,
    Colors.magenta,
    Colors.cyan,
  )

  private def colorOfString(s: String): String = colors(s.hashCode.abs % colors.length)(s)

  private def isVisiblyEmpty(s: String): Boolean =
    s.filterNot(_.isControl).isEmpty

  // decorate lines with a prefix
  // we use string.split from javascript, because it behaves saner than java.
  // java:
  // scala> "\na\nb\nc".split("\n")
  // val res23: Array[String] = Array("", a, b, c)
  // scala> "\na\nb\nc\n".split("\n")
  // val res24: Array[String] = Array("", a, b, c)
  // javascript:
  // > "\na\nb\nc\n".split("\n")
  // [ '', 'a', 'b', 'c', '' ]
  // > "\na\nb\nc".split("\n")
  // [ '', 'a', 'b', 'c' ]
  def addPrefixToLines(s: String, prefix: String): String = if (isVisiblyEmpty(s)) s
  else {
    val decorateF: String => String           = s => colorOfString(prefix) + s
    val decorateIfNonEmptyF: String => String = s => if (isVisiblyEmpty(s)) s else decorateF(s)

    import JSStringOps._
    val split = s.jsSplit(System.lineSeparator())
    val decorated  = split.toArray match {
      case Array() => Array()
      case Array(a) => Array(decorateIfNonEmptyF(a))
      case array => Array(decorateIfNonEmptyF(array.head)) ++ array.drop(1).dropRight(1).map(decorateF) ++ Array(decorateIfNonEmptyF(array.last))
    }

    decorated.mkString(System.lineSeparator())
  }
}
