package terraverse

import cats.effect.implicits._
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import terraverse.cli.UserPrompt
import terraverse.config.{ConfigReader, Context, DependencyGraph, LoadedConfig}
import terraverse.logging.Logger
import terraverse.template.Templating
import terraform.{TerraformExecutor, TerraformProject}
import terraverse.yaml.Yaml
import typings.colors.{safeMod => Colors}
import typings.node.pathMod
import typings.node.processMod.global.process

import scala.collection.mutable
import scala.scalajs.js

object Main extends IOApp {

  def run(@annotation.unused _args: List[String]): IO[ExitCode] = {
    val args = process.argv.toArray.drop(2) // ignore node and filename args

    val appConfigLoaded = AppConfig.command
      .parseFromEnv(AppConfig.default)
      .flatMap(AppConfig.command.parse(_, args))

    appConfigLoaded match {
      case Left(error)      =>
        Logger.error(s"Error while parsing arguments: $error\n\n${AppConfig.command.help}")
        IO.pure(ExitCode.Error)
      case Right(appConfig) =>
        Logger.level = appConfig.logLevel

        if (appConfig.showHelp) {
          Logger.log(AppConfig.command.help)
          IO.pure(ExitCode.Success)
        }
        else if (appConfig.showVersion) {
          Logger.log(AppConfig.version)
          IO.pure(ExitCode.Success)
        }
        else {
          Logger.trace(Colors.green(s"\nTerramind configuration:\n"))
          Logger.trace(pprint.tokenize(appConfig, showFieldNames = true, indent = 2).mkString)
          program(appConfig).as(ExitCode.Success)
        }
    }
  }

  def runInit(appConfig: AppConfig, config: LoadedConfig): IO[Unit] =
    for {
      _           <- IO(Logger.trace(Colors.green(s"\nConfiguration: ${config.filePathRelative}\n\n") + Yaml.dumpYaml(config.config)))
      _           <- IO(Logger.info(Colors.green(s"\nInit terraform: ${config.nameRelative}\n")))
      _           <- TerraformProject
        .setup(appConfig, config)
        // TODO proper error
        .map(_.left.map(e => new Exception(s"Error generating terraform project: $e")))
        .rethrow
    } yield ()

  def runOutput(appConfig: AppConfig, config: LoadedConfig, needOutput: Boolean): IO[Option[js.Any]] =
    for {
      output      <-
        if (needOutput) TerraformExecutor.terraformToOutput(appConfig, config.nameRelative, config.terraformPath, List("output", "-json")).map(Some.apply)
        else IO.none
      decoded     <- output.traverse(s => IO(js.JSON.parse(s).asInstanceOf[js.Dictionary[js.Dictionary[js.Any]]]))
      decodedValue = decoded.flatMap(_.get(TerraformProject.outputName)).flatMap(_.get("value"))
    } yield decodedValue

  def runConfig(requestedTargetFiles: Set[String], appConfig: AppConfig, config: LoadedConfig): IO[Unit] =
    for {
      _           <- IO.whenA(appConfig.commands.nonEmpty && (appConfig.runAll || requestedTargetFiles.contains(config.filePath)))(
                       IO(Logger.info(Colors.green(s"\nRun terraform: ${config.nameRelative}\n"))) *>
                         TerraformExecutor.terraformInForeground(
                           appConfig,
                           config.nameRelative,
                           config.terraformPath,
                           appConfig.commands,
                           forwardStdIn = appConfig.parallelism == 1,
                         ),
                     )
    } yield ()

  def program(appConfig: AppConfig): IO[Unit] = {
    val targetsWithDefault = if (appConfig.targets.isEmpty) List(".") else appConfig.targets
    val configs            = targetsWithDefault.flatTraverse(ConfigReader.readConfigs(appConfig, _))

    configs match {
      case Left(error)          =>
        Logger.error(s"Error while reading configuration files: $error")
        IO.pure(ExitCode.Error)
      case Right(targetConfigs) =>
        val requestedTargetFiles  = targetConfigs.map(_.filePath).toSet

        val dependencyResolutions = DependencyGraph.resolve(appConfig, targetConfigs).flatTraverse { dependencyGraph =>
          val allTargetFiles          = dependencyGraph.entries.flatMap(_.map(_.config.filePath)).toSet
          val allRequestedTargetFiles = allTargetFiles.intersect(requestedTargetFiles)

          val dependencyLog = dependencyGraph.entries.zipWithIndex.map { case (d, i) =>
            s"Group ${i + 1} ${d.map(_.config.filePathRelative).mkString("\n\t- ", "\n\t- ", "")}"
          }.mkString("\n\n- ", "\n- ", "")

          Logger.info(Colors.yellow("\nDependency graph:") + dependencyLog)

          val userConfirmed = UserPrompt
            .confirmIf(appConfig.prompt && appConfig.runAll && allTargetFiles.size > allRequestedTargetFiles.size)(
              Colors.red(s"\nAre you sure you want to run on all ${allTargetFiles.size} target files?"),
            )

          userConfirmed.map {
            case true  => Right(dependencyGraph.entries)
            case false => Left("User aborted.")
          }
        }

        dependencyResolutions.flatMap {
          case Left(error)         =>
            Logger.error(s"Error resolving dependencies: $error")
            IO.pure(ExitCode.Error)
          case Right(dependencies) =>
            // TODO: This should be done properly in some terraform related scala files (plugin system)
            val shouldReverseRun = appConfig.commands.contains("destroy")

            val allOutputs = mutable.HashMap.empty[String, js.Any]

            val resolvedConfigs: IO[Seq[Seq[LoadedConfig]]] = dependencies.traverse { dependencyBatch =>
              dependencyBatch
                .parTraverseN(appConfig.parallelism) { dependency =>
                  val contextDependencies = dependency.config.config.dependencies.map(_.toMap.flatMap { case (name, file) =>
                    val absoluteFile = pathMod.resolve(dependency.config.dirPath, file)
                    allOutputs.get(absoluteFile).map(name -> _)
                  })

                  val context = Context(
                    dependencyOutputs = contextDependencies.getOrElse(Map.empty),
                  )

                  val resolvedConfig = Templating.replaceVariables(dependency.config, context)

                  resolvedConfig.flatMap { config =>
                    val run =
                      runInit(appConfig, config) *>
                        IO.whenA(!shouldReverseRun)(runConfig(requestedTargetFiles, appConfig, config)) *>
                        runOutput(appConfig, config, needOutput = dependency.hasDependee).map(_.map(config.filePath -> _))

                    run
                      .flatTap(_.traverse_ { case (k, v) => IO(allOutputs.update(k, v)) })
                      .uncancelable
                      .as(config)
                  }
                }
            }

              resolvedConfigs.flatMap { dependencies =>
                IO.whenA(shouldReverseRun)(dependencies.reverse.traverse_ { dependencyBatch =>
                  dependencyBatch.parTraverseN(appConfig.parallelism) { config =>
                    runConfig(requestedTargetFiles, appConfig, config)
                  }
                })
              }.void
        }
    }
  }
}
