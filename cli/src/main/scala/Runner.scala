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

  def runOutput(appConfig: AppConfig, config: LoadedConfig, needOutput: Boolean): IO[Option[js.Any]] =
    for {
      output      <-
        if (needOutput) TerraformExecutor.terraformToOutput(appConfig, config.nameRelative, config.terraformPath, List("output", "-json")).map(Some.apply)
        else IO.none
      decoded     <- output.traverse(s => IO(js.JSON.parse(s).asInstanceOf[js.Dictionary[js.Dictionary[js.Any]]]))
      decodedValue = decoded.flatMap(_.get(TerraformProject.outputName)).flatMap(_.get("value"))
    } yield decodedValue

  def runConfig(requestedTargetFiles: Set[String], appConfig: AppConfig, config: LoadedConfig): IO[Unit] =
      IO.whenA(appConfig.commands.nonEmpty && (appConfig.runAll || requestedTargetFiles.contains(config.filePath)))(
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

      val userConfirmed = UserPrompt
        .confirmIf(appConfig.prompt && allTargetFiles.size > allRequestedTargetFiles.size)(
          Colors.red(s"\nAre you sure you want to run on all ${allTargetFiles.size} target files?"),
        )

      userConfirmed match {
        case true => Right(dependencyGraph.entries)
        case false => Left("User aborted.")
      }
    }

  def runOverTargets(requestedTargetFiles: Set[String], appConfig: AppConfig, dependencies: Seq[Seq[DependencyGraphEntry]]): IO[Unit] = {
    val shouldReverseRun = appConfig.commands.contains("destroy")

    val allOutputs = mutable.HashMap.empty[String, js.Any]

    val resolvedConfigs: IO[Seq[Seq[LoadedConfig]]] = dependencies.traverse { dependencyBatch =>
      val resolvedConfigs = dependencyBatch
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

      resolvedConfigs
        .flatMap(_.parTraverseIf(appConfig.parallelRun) { case (dependency, config) =>
          val run =
            IO.unlessA(shouldReverseRun)(runConfig(requestedTargetFiles, appConfig, config)) *>
              //TODO potential optimization: parse output from run
              runOutput(appConfig, config, needOutput = dependency.hasDependee).map(_.map(config.filePath -> _))

          run
            .flatTap(_.traverse_ { case (k, v) => IO(allOutputs.update(k, v)) })
            .as(config)
        })


    }

    resolvedConfigs.flatMap { dependencies =>
      IO.whenA(shouldReverseRun)(dependencies.reverse.traverse_ { dependencyBatch =>
        dependencyBatch.parTraverseIf(appConfig.parallelRun) { config =>
          runConfig(requestedTargetFiles, appConfig, config)
        }
      })
    }.void
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
