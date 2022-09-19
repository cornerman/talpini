package talpini.native

import cats.effect.IO
import talpini.AppConfig
import talpini.logging.Logger
import typings.colors.{safeMod => Colors}
import typings.node.NodeJS.ReadableStream
import typings.node.processMod
import typings.node.childProcessMod
import typings.node.childProcessMod.{ChildProcess, StdioPipeNamed}

import scala.scalajs.js

object ShellExecutor {
  def execute(appConfig: AppConfig, name: String, path: String, commands: Seq[String]/*, forwardStdIn: Boolean*/): IO[Unit] = IO.async_ { cb =>
    val shellCmd: String = processMod.env.get("SHELL").flatMap(_.toOption).getOrElse("/bin/sh")

    val args = if (commands.isEmpty) js.Array[String]() else js.Array("-c", commands.mkString(" "))
    val options = childProcessMod.SpawnOptionsWithoutStdio().setCwd(path).setStdio(StdioPipeNamed.pipe)

    if (commands.isEmpty) {
      Logger.info(s"\n${Colors.bold(s"New interactive Shell: ${name}")}\nTo proceed, press CTRL-D or type exit.")
      options.asInstanceOf[js.Dynamic].stdio = "inherit"
    }

    Logger.info(s"Exec: ${shellCmd} ${args.mkString(" ")}")
    val childProcess = childProcessMod.spawn(shellCmd, args, options)

    if (!commands.isEmpty) {
      val decorateLines: String => String =
        if (appConfig.decorateCommandOutput) StringOutput.addPrefixToLines(_, s"[$name] ")
        else identity

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
    }

    val _ = childProcess
      .asInstanceOf[ReadableStream]
      .on(
        "exit",
        { code =>
          if (code == 0) cb(Right(()))
          else if (commands.isEmpty) cb(Right(()))
          else cb(Left(new Exception(s"Shell returned error code: $code")))
        }
      )
  }
}
