package talpini

import cats.implicits._
import talpini.cli._
import talpini.logging.LogLevel

import scala.scalajs.js

case class AppConfig(
  showVersion: Boolean = false,
  showHelp: Boolean = false,
  runAll: Boolean = false,
  parallelRun: Boolean = false,
  parallelInit: Boolean = true,
  targets: List[String] = Nil,
  autoIncludes: List[String] = List("talpini.yaml", "talpini.yml"),
  commands: List[String] = Nil,
  prompt: Boolean = true,
  cache: Boolean = true,
  logLevel: LogLevel = LogLevel.Info,
  terraformCmd: String = "terraform",
  terraformInitArgs: List[String] = List("-reconfigure"),
  decorateTerraformOutput: Boolean = true,
)
object AppConfig {

  val version = js.Dynamic.global.require("./package.json").version.asInstanceOf[String]

  val default = AppConfig()

  val command = Command[Either[String, *], AppConfig](
    name = "talpini",
    options = List(
      CliOpt.Flag(
        OptId("h", "help"),
        "show this message.",
        config => Right(config.copy(showHelp = true)),
      ),
      CliOpt.Flag(
        OptId("v", "version"),
        "show version.",
        config => Right(config.copy(showVersion = true)),
      ),
      CliOpt.Arg(
        OptId("t", "target"),
        "Select target file/directory (default: ./). in case of a file, it only runs on this file. If you specify a directory, it recursively searches for any *.t.yml (or yaml) file.",
        (config, arg) => Right(config.copy(targets = config.targets :+ arg)),
      ),
      CliOpt.Flag(
        OptId("p", "parallel-run"),
        "Execute terraform runs in parallel. So you can process independent groups of terraform modules in parallel. stdin is disabled - so you cannot confirm user-prompts from terraform.",
          (config) => Right(config.copy(parallelRun = true)),
      ),
      CliOpt.Flag(
        OptId.long("no-parallel-init"),
        "Execute project setup and terraform init in parallel. So you can process independent groups of terraform modules in parallel.",
        (config) => Right(config.copy(parallelInit = false)),
      ),
      CliOpt.Flag(
        OptId("y", "yes"),
        "Automatically say yes to all talpini prompts. This has no influence on terraform prompts. Example for apply without any user-prompts: talpini -y apply -auto-approve.",
        config => Right(config.copy(prompt = false)),
      ),
      CliOpt.Flag(
        OptId("q", "quiet"),
        "Only write errors to stderr and plain terraform output to stdout. Set log-level to error.",
        config => Right(config.copy(logLevel = LogLevel.Error, decorateTerraformOutput = false)),
      ),
      CliOpt.Arg(
        OptId("l", "log-level"),
        "Select log level: trace, debug, info (default), warn, error.",
        (config, arg) => LogLevel.fromString(arg).map(l => config.copy(logLevel = l)),
      ),
      CliOpt.Arg(
        OptId.long("auto-include"),
        "Per default files called talpini.yml (or .yaml) within and above the current directory are auto-included. You can add additional auto-includes, e.g. --auto-include dev.yml.",
        (config, arg) => Right(config.copy(autoIncludes = config.autoIncludes :+ arg)),
      ),
      CliOpt.Arg(
        OptId.long("init-arg"),
        "Specify which args to append to terraform init.",
        (config, arg) => Right(config.copy(terraformInitArgs = config.terraformInitArgs :+ arg)),
      ),
      CliOpt.Arg(
        OptId.long("terraform-cmd"),
        "Specify which terraform command to execute.",
        (config, arg) => Right(config.copy(terraformCmd = arg)),
      ),
      CliOpt.Flag(
        OptId.long("run-all"),
        "Run on all dependent configuration, not just the ones specified by target.",
        config => Right(config.copy(runAll = true)),
      ),
      CliOpt.Flag(
        OptId.long("no-cache"),
        "Recreate cached terraform projects.",
        config => Right(config.copy(cache = false)),
      ),
    ),
    tail = CliTail.ArgsWithSeparator(
      "--",
      "any terraform arguments.",
      (config, args) => Right(config.copy(commands = args)),
    ),
  )
}
