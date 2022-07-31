package talpini

import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import talpini.logging.Logger
import typings.colors.{safeMod => Colors}
import typings.node.processMod.global.process

import scala.annotation.unused

object Main extends IOApp {

  def run(@unused _args: List[String]): IO[ExitCode] = {
    val args = process.argv.toArray.drop(2) // ignore node and filename args

    val appConfigLoaded = Right(AppConfig.default)
      .flatMap(AppConfig.command.parseFromEnv(_))
      .flatMap(AppConfig.command.parse(_, args))

    appConfigLoaded match {
      case Left(error)      =>
        Logger.error(s"Error while parsing arguments: $error\n\n${AppConfig.command.help}")
        IO.pure(ExitCode.Error)
      case Right(appConfig) =>
        Logger.level = appConfig.logLevel

        Logger.trace(Colors.green(s"\nTalpini configuration:\n"))
        Logger.trace(pprint.tokenize(appConfig, showFieldNames = true, indent = 2).mkString)

        (appConfig.showHelp, appConfig.showVersion) match {
          case (true, _) =>
            Logger.log(AppConfig.command.help)
            IO.pure(ExitCode.Success)
          case (_, true) =>
            Logger.log(AppConfig.version)
            IO.pure(ExitCode.Success)
          case (_, _) =>
            Runner.program(appConfig)
              .recover { case err =>
                Logger.error(s"\n${Colors.red("Cannot continue, there was an error :/")}\n\n\t${err.getMessage}")
                ExitCode.Success
              }
        }
    }
  }
}
