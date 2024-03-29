package talpini.terraform

import cats.effect.IO
import talpini.native.StringOutput
import talpini.AppConfig
import talpini.logging.Logger
import typings.node.NodeJS.ReadableStream
import typings.node.anon.End
import typings.node.childProcessMod.ChildProcessWithoutNullStreams
import typings.node.{childProcessMod, pathMod, processMod}

import scala.scalajs.js

object TerraformExecutor {
  
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
        if (appConfig.decorateCommandOutput) StringOutput.addPrefixToLines(_, s"[$name] ")
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
        if (appConfig.decorateCommandOutput) StringOutput.addPrefixToLines(_, s"[$name] ")
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
        processMod.stdin.asInstanceOf[js.Dynamic].setRawMode.asInstanceOf[js.UndefOr[js.Function1[Boolean, Unit]]].fold {
          Logger.warn("Failed to forward stdin to terraform process")
        } { setRawMode =>
          setRawMode.call(processMod.stdin, false)
          processMod.stdin.asInstanceOf[ReadableStream].pipe(childProcess.stdin, End().setEnd(false))
          ()
        }
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
