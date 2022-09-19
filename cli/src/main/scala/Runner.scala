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
import talpini.native.ShellExecutor
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

  def runConfig(requestedTargetFiles: Set[String], appConfig: AppConfig, config: LoadedConfig): IO[Unit] = {
    if (config.config.enabled && (appConfig.runAll || requestedTargetFiles.contains(config.filePath))) {
      if (appConfig.launchShell) {
        IO(Logger.info(Colors.green(s"\nLaunch shell: ${config.nameRelative}\n"))) *>
          ShellExecutor.execute(
            appConfig,
            config.nameRelative,
            config.terraformPath,
            appConfig.commands,
          )
      } else if (appConfig.commands.nonEmpty) {
        IO(Logger.info(Colors.green(s"\nRun terraform: ${config.nameRelative}\n"))) *>
          TerraformExecutor.terraformInForeground(
            appConfig,
            config.nameRelative,
            config.terraformPath,
            appConfig.commands,
            forwardStdIn = !appConfig.parallelRun,
          )
      } else IO.unit
    } else IO.unit
  }

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
          val directDependencies = dependency.loadedConfig.config.dependencies.toList.flatMap(_.toList)
          val directDependenciesOutputs = directDependencies.traverse { case (name, file) =>
            val absoluteFile = pathMod.resolve(dependency.loadedConfig.dirPath, file)
            allOutputs.get(absoluteFile).map(name -> _)
          }

          directDependenciesOutputs match {
            case Some(dependencyOutputs) =>
              val context = Context(
                dependencyOutputs = dependencyOutputs.toMap
              )

              val resolvedConfig = Templating.replaceVariables(dependency.loadedConfig, context)

              resolvedConfig.flatTap { config =>
                runInit(appConfig, config)
              }.map(c => Some(dependency -> c))

            case None if isDestroyRun =>
              Logger.info(s"Skipping module '${dependency.loadedConfig.name}', because its dependencies are already destroyed.")
              IO.pure(None)

            case None =>
              Logger.error(s"Cannot generate module '${dependency.loadedConfig.name}', because its dependencies are missing. Run 'apply' with '--run-all' to apply this target with all its dependencies.")
              IO.raiseError(new Exception(s"Missing dependencies for module '${dependency.loadedConfig.name}'"))
          }
        }.map(_.flatten)

      val executedConfigs: IO[Seq[(DependencyGraphEntry, LoadedConfig)]] =
        if (isDestroyRun) initializedConfigs
        else initializedConfigs.flatMap(_.parTraverseIf(appConfig.parallelRun) { case input@(_, config) =>
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
