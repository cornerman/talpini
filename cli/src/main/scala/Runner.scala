package talpini

import cats.effect.{ExitCode, IO}
import cats.implicits._
import t.yaml.Yaml
import talpini.cli.UserPrompt
import talpini.config._
import talpini.implicits._
import talpini.logging.Logger
import talpini.template.Templating
import talpini.terraform.{TerraformExecutor, TerraformProject}
import typings.colors.{safeMod => Colors}
import typings.node.pathMod

import scala.collection.mutable
import scala.scalajs.js

object Runner {
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

  def runOutput(appConfig: AppConfig, config: LoadedConfig): IO[Option[js.Any]] =
    for {
      output      <- TerraformExecutor.terraformToOutput(appConfig, config.nameRelative, config.terraformPath, List("output", "-json"))
      decoded     <- IO(js.JSON.parse(output).asInstanceOf[js.Dictionary[js.Dictionary[js.Any]]])
    } yield decoded.get(TerraformProject.outputName).flatMap(_.get("value"))

  def runConfig(requestedTargetFiles: Set[String], appConfig: AppConfig, config: LoadedConfig): IO[Unit] =
      IO.whenA(appConfig.commands.nonEmpty && config.config.enabled && (appConfig.runAll || requestedTargetFiles.contains(config.filePath)))(
        IO(Logger.info(Colors.green(s"\nRun terraform: ${config.nameRelative}\n"))) *>
          TerraformExecutor.terraformInForeground(
            appConfig,
            config.nameRelative,
            config.terraformPath,
            appConfig.commands,
            forwardStdIn = !appConfig.parallelRun,
          ),
      )

  def selectDependencyGroups(requestedTargetFiles: Set[String], appConfig: AppConfig, targetConfigs: List[LoadedConfigRaw]) =
    DependencyGraph.resolve(appConfig, targetConfigs).flatMap { dependencyGraph =>
      val allTargetFiles = dependencyGraph.entries.flatMap(_.map(_.loadedConfig.filePath)).toSet
      val allRequestedTargetFiles = allTargetFiles.intersect(requestedTargetFiles)

      val dependencyLog = dependencyGraph.entries.zipWithIndex.map { case (d, i) =>
        s"Group ${i + 1} ${d.map(_.loadedConfig.filePathRelative).mkString("\n\t- ", "\n\t- ", "")}"
      }.mkString("\n\n- ", "\n- ", "")

      Logger.info(Colors.yellow("\nDependency graph:") + dependencyLog)

      val userConfirmed =
        (!appConfig.prompt || !appConfig.runAll || allTargetFiles.size == allRequestedTargetFiles.size) ||
          UserPrompt.confirm(
            Colors.red(s"\nAre you sure you want to run on all ${allTargetFiles.size} target files?"),
          )

      userConfirmed match {
        case true => Right(dependencyGraph.entries)
        case false => Left("User aborted.")
      }
    }

  def runOverTargets(requestedTargetFiles: Set[String], appConfig: AppConfig, dependencies: Seq[Seq[DependencyGraphEntry]]): IO[Unit] = {
    val isDestroyRun = appConfig.commands.contains("destroy")

    val allOutputs = mutable.HashMap.empty[String, js.Any]

    val executedConfigs: IO[Seq[Seq[LoadedConfig]]] = dependencies.traverse { dependencyBatch =>
      val initializedConfigs: IO[Seq[(DependencyGraphEntry, LoadedConfig)]] = dependencyBatch
        .parTraverseIf(appConfig.parallelInit) { dependency =>
          val contextDependencies = dependency.loadedConfig.config.dependencies.map(_.toMap.flatMap { case (name, file) =>
            val absoluteFile = pathMod.resolve(dependency.loadedConfig.dirPath, file)
            allOutputs.get(absoluteFile).map(name -> _)
          })

          val context = Context(
            dependencyOutputs = contextDependencies.getOrElse(Map.empty),
          )

          val resolvedConfig = Templating.replaceVariables(dependency.loadedConfig, context)

          resolvedConfig.flatTap { config =>
            runInit(appConfig, config)
          }.map((dependency, _))
        }

      val executedConfigs: IO[Seq[(DependencyGraphEntry, LoadedConfig)]] =
        if (isDestroyRun) initializedConfigs
        else initializedConfigs.flatMap(_.parTraverseIf(appConfig.parallelRun) { case input@(dependency, config) =>
          runConfig(requestedTargetFiles, appConfig, config).as(input)
        })

      executedConfigs.flatMap(_.parTraverseIf(appConfig.parallelInit) { case (dependency, config) =>
        IO.whenA(dependency.hasDependee)(
          //TODO potential optimization: parse output from run
          runOutput(appConfig, config)
            .flatMap(_.traverse_(output => IO(allOutputs.update(config.filePath, output))))
        ).as(config)
      })
    }

    if (isDestroyRun) executedConfigs.flatMap { dependencies =>
      dependencies.reverse.traverse_ { dependencyBatch =>
        dependencyBatch.parTraverseIf(appConfig.parallelRun) { config =>
          runConfig(requestedTargetFiles, appConfig, config).as(config)
        }
      }
    } else executedConfigs.void
  }

  def program(appConfig: AppConfig): IO[ExitCode] = {
    val targetsWithDefault = if (appConfig.targets.isEmpty) List(".") else appConfig.targets
    val configs            = targetsWithDefault.flatTraverse(ConfigReader.readConfigs(appConfig, _))

    configs match {
      case Left(error)          =>
        Logger.error(s"Error while reading configuration files: $error")
        IO.pure(ExitCode.Error)
      case Right(targetConfigs) =>
        val requestedTargetFiles = targetConfigs.map(_.filePath).toSet
        selectDependencyGroups(requestedTargetFiles, appConfig, targetConfigs) match {
          case Left(error)         =>
            Logger.error(s"Error resolving dependencies: $error")
            IO.pure(ExitCode.Error)
          case Right(dependencyGroups) =>
            val requestedTargetFiles = targetConfigs.map(_.filePath).toSet
            runOverTargets(requestedTargetFiles, appConfig, dependencyGroups)
              .as(ExitCode.Success)
        }
    }
  }
}
