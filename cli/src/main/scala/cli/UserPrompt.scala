package terraverse.cli

import cats.effect.IO
import typings.node.NodeJS.{ReadableStream, WritableStream}
import typings.node.processMod.global.process
import typings.node.readlineMod

object UserPrompt {

  private val readline = readlineMod.createInterface(process.stdin.asInstanceOf[ReadableStream], process.stdout.asInstanceOf[WritableStream])

  def confirmIf(when: Boolean)(question: String): IO[Boolean] = if (when) {
    val askUser = IO.async_[String](cb => readline.question(question + " (y/N): ", data => cb(Right(data))))

    askUser.map {
      case "y" | "yes" => true
      case _           => false
    }
  }
  else IO.pure(true)

  def confirmAlways(question: String): IO[Boolean] = confirmIf(true)(question)
}
