package talpini.terraform

import cats.effect.IO
import talpini.AppConfig
import talpini.logging.Logger
import typings.colors.{safeMod => Colors}
import typings.node.NodeJS.ReadableStream
import typings.node.anon.End
import typings.node.childProcessMod.ChildProcessWithoutNullStreams
import typings.node.{childProcessMod, pathMod, processMod}

import scala.scalajs.js
import scala.scalajs.js.JSStringOps

object TerraformExecutor {

  private val colors = Array[String => String](
    Colors.red,
    Colors.green,
    Colors.yellow,
    Colors.blue,
    Colors.magenta,
    Colors.cyan,
  )

  private def colorOfString(s: String): String = colors(s.hashCode.abs % colors.length)(s)

  private def isVisiblyEmpty(s: String): Boolean = s.filterNot(_.isControl).isEmpty

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
  private def addPrefixToLines(s: String, prefix: String): String = if (isVisiblyEmpty(s)) ""
  else {
    val decorateF: String => String           = s => colorOfString(prefix) + s
    val decorateIfNonEmptyF: String => String = s => if (isVisiblyEmpty(s)) s else decorateF(s)

    import JSStringOps._
    val split = s.jsSplit(System.lineSeparator())

    val decorated = split.init.map(decorateF) ++ split.lastOption.map(decorateIfNonEmptyF)

    decorated.mkString(System.lineSeparator())
  }

  private def spawnTerraform(appConfig: AppConfig, path: String, commands: Seq[String]): ChildProcessWithoutNullStreams = {
    val args = js.Array(s"""-chdir=${pathMod.relative(".", path)}""") ++ commands
    Logger.info(s"Exec: ${appConfig.terraformCmd} ${args.mkString(" ")}")
    childProcessMod.spawn(appConfig.terraformCmd, args)
  }

  def terraformToOutput(appConfig: AppConfig, name: String, path: String, commands: Seq[String]): IO[String] =
    IO.async_[String] { cb =>
      val childProcess = spawnTerraform(appConfig, path, commands)

      val output    = new StringBuilder
      val allOutput = new StringBuilder

      val decorateLines: String => String =
        if (appConfig.decorateTerraformOutput) addPrefixToLines(_, s"[$name] ")
        else identity

      val _ = childProcess.stdout
        .asInstanceOf[ReadableStream]
        .on(
          "data",
          { data =>
            val _ = output.addAll(data.toString)
            val _ = allOutput.addAll(data.toString)
          },
        )

      val _ = childProcess.stderr
        .asInstanceOf[ReadableStream]
        .on(
          "data",
          { data =>
            val _ = allOutput.addAll(data.toString)
          },
        )

      val _ = childProcess
        .asInstanceOf[ReadableStream]
        .on(
          "exit",
          code =>
            if (code == 0) cb(Right(output.result()))
            else {
              Logger.info(decorateLines(allOutput.result()))
              cb(Left(new Exception(s"Terraform returned error code: $code")))
            },
        )
    }.uncancelable

  def terraformInForeground(appConfig: AppConfig, name: String, path: String, commands: Seq[String], forwardStdIn: Boolean): IO[Unit] =
    IO.async_[Unit] { cb =>
      val childProcess = spawnTerraform(appConfig, path, commands)

      val decorateLines: String => String =
        if (appConfig.decorateTerraformOutput) addPrefixToLines(_, s"[$name] ")
        else identity

      // childProcess.stdout.asInstanceOf[ReadableStream].pipe(processMod.stdout)
      // childProcess.stderr.asInstanceOf[ReadableStream].pipe(processMod.stderr)
      val _ = childProcess.stdout
        .asInstanceOf[ReadableStream]
        .on(
          "data",
          { data =>
            val _ = processMod.stdout.asInstanceOf[js.Dynamic].write(decorateLines(data.toString))
          },
        )

      val _ = childProcess.stderr
        .asInstanceOf[ReadableStream]
        .on(
          "data",
          { data =>
            val _ = processMod.stderr.asInstanceOf[js.Dynamic].write(decorateLines(data.toString))
          },
        )

      if (forwardStdIn) {
        processMod.stdin.asInstanceOf[js.Dynamic].setRawMode(false)
        processMod.stdin.asInstanceOf[ReadableStream].pipe(childProcess.stdin, End().setEnd(false))
      }

      val _ = childProcess
        .asInstanceOf[ReadableStream]
        .on(
          "exit",
          code =>
            if (code == 0) cb(Right(()))
            else cb(Left(new Exception(s"Terraform returned error code: $code"))),
        )
    }.uncancelable
}
