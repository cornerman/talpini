package talpini.terraform

import cats.effect.IO
import cats.implicits._
import talpini.AppConfig
import talpini.cloud.Backend
import talpini.config.{ConfigBackend, LoadedConfig}
import talpini.logging.Logger
import talpini.native.{FileSystem, JsNative}
import typings.glob.{mod => glob}
import typings.node.{fsMod, pathMod}

import scala.scalajs.js

object TerraformProject {

  val outputName        = "module"
  val terraformDir      = ".terraform" // TODO: this could be changed? TF_DATA_DIR?
  val terraformLockFile = ".terraform.lock.hcl"

  val generatedHeader = "# Generated by talpini"

  def digestFilePath(basePath: String)      = pathMod.resolve(basePath, "__talpini_digest")
  def initSuccessFilePath(basePath: String) = pathMod.resolve(basePath, "__talpini_init_success")
  def moduleFilePath(basePath: String)      = pathMod.resolve(basePath, "__talpini_module.tf")
  def outputFilePath(basePath: String)      = pathMod.resolve(basePath, "__talpini_outputs.tf")
  def backendFilePath(basePath: String)     = pathMod.resolve(basePath, "__talpini_backend.tf")
  def providersFilePath(basePath: String)   = pathMod.resolve(basePath, "__talpini_providers.tf")

  def newLineIndent(indent: Int) = System.lineSeparator() + (" " * 2 * indent)

  def jsGetValueAsTerraformString(any: js.Any, indent: Int): String = {
    if (!JsNative.isDefined(any)) {
      null
    }
    else if (JsNative.isString(any)) {
      val str = any.asInstanceOf[String]
      js.JSON.stringify(str)
    }
    else if (JsNative.isArray(any)) {
      val arr = any.asInstanceOf[js.Array[js.Any]]
      s"[${arr.map(jsGetValueAsTerraformString(_, indent + 1)).mkString(newLineIndent(indent + 1), "," + newLineIndent(indent + 1), newLineIndent(indent))}]" // TODO better?
    }
    else if (JsNative.isObject(any)) {
      val obj = any.asInstanceOf[js.Dictionary[js.Any]]
      s"{${obj.toMap.map { case (k, v) => s"$k = ${jsGetValueAsTerraformString(v, indent + 1)}" }.mkString(newLineIndent(indent + 1), newLineIndent(indent + 1), newLineIndent(indent))}}" // TODO: better?
    }
    else {
      any.toString // maybe better to exhaustively check and error out
    }
  }

  def jsGetFieldsAsTerraformStringsWithBlock(value: js.Dictionary[js.Any], indent: Int): Iterable[String] = value.toMap.map { case (k, v) =>
    val separator = if (JsNative.isObject(v)) " " else " = "
    s"$k$separator${jsGetValueAsTerraformString(v, indent)}"
  }

  def generateTerraformOutput(config: LoadedConfig): String =
    s"""${generatedHeader}
       |output "${outputName}" {
       |  value = module.${config.name}
       |  sensitive = ${config.config.sensitive}
       |}
       |""".stripMargin

  def generateTerraformBackend(config: LoadedConfig): String =
    s"""${generatedHeader}
       |terraform {
       |  backend "${config.config.backend.get.tpe}" {
       |    ${jsGetFieldsAsTerraformStringsWithBlock(config.config.backend.get.config, indent = 2).mkString(newLineIndent(2))}
       |  }
       |}
       |""".stripMargin

  def generateTerraformProviders(config: LoadedConfig): String = {
    def generateProvider(key: String, value: js.Dictionary[js.Any]): String =
      s"""
         |provider "$key" {
         |  ${jsGetFieldsAsTerraformStringsWithBlock(value, indent = 1).mkString(newLineIndent(1))}
         |}
         |""".stripMargin

    s"""${generatedHeader}
       |${config.config.providers.flatMap { case (k, v) => v.map(generateProvider(k, _)).toSeq }.mkString(newLineIndent(0))}
       |""".stripMargin
  }

  def generateTerraformModule(path: String, config: LoadedConfig): String = {
    val moduleMapDefaults = Map[String, js.Any]("source" -> pathMod.relative(path, pathMod.dirname(config.filePath)))

    val fields = (moduleMapDefaults ++ config.config.module).map {
      // module meta arguments: https://www.terraform.io/language/meta-arguments/module-providers
      // Because they expect a static map (not jsondecode). And expect terraform identifiers to not be quoted.
      case (k @ "depends_on", v) if JsNative.isArray(v) =>
        val dependsOn = v.asInstanceOf[js.Array[js.Any]]
        s"$k = [${dependsOn.mkString(", ")}]"

      case (k @ "providers", v) if JsNative.isObject(v) =>
        val providers = v.asInstanceOf[js.Dictionary[String]]
        s"$k = {${providers.map { case (k, v) => s"$k = $v" }.mkString(", ")}}"

      case (k, v) => s"$k = ${jsGetValueAsTerraformString(v, 1)}"
    }

    s"""${generatedHeader}
       |module "${config.name}" {
       |  ${fields.mkString(newLineIndent(1))}
       |}
       |""".stripMargin
  }

  def setup(appConfig: AppConfig, config: LoadedConfig): IO[Either[String, Unit]] = {
    Logger.info(s"Setup terraform project ${config.terraformPathRelative}")
    val digest = config.digest
    val path   = config.terraformPath

    val digestFile      = digestFilePath(path)
    val moduleFile      = moduleFilePath(path)
    val outputFile      = outputFilePath(path)
    val backendFile     = backendFilePath(path)
    val providersFile   = providersFilePath(path)
    val initSuccessFile = initSuccessFilePath(path)

    val terraformStateFile = config.config.backend
      .flatMap[String] {
        // TODO: respect workspace_dir?
        case local if local.tpe == ConfigBackend.Local.tpeName => local.asInstanceOf[ConfigBackend.Local].path
        case _                                                 => js.undefined
      }
      .getOrElse("terraform.tfstate")

    val existingLockFile  = pathMod.resolve(config.dirPath, s".${config.name}.${terraformLockFile.dropWhile(_ == '.')}")
    val activeLockFile    = pathMod.resolve(path, terraformLockFile)
    val existingStateFile = pathMod.resolve(config.dirPath, s".${config.name}.${terraformStateFile.dropWhile(_ == '.')}")
    val activeStateFile   = pathMod.resolve(path, terraformStateFile)

    val fs = new FileSystem(readPath = config.dirPath, writePath = config.terraformPath)

    val shouldCreateProject = for {
      fileDigest         <- IO(fs.readFile(digestFile))
      projectIsUpToDate   = fileDigest.exists(_ == digest)
      shouldCreateProject = !appConfig.cache || !projectIsUpToDate
      _                  <- IO(Logger.debug(s"Current digest: $digest. Is updated? ${projectIsUpToDate}. Should create? ${shouldCreateProject}."))
    } yield shouldCreateProject

    val shouldInitialize = IO(!fsMod.existsSync(initSuccessFile))

    val setupFiles: IO[Either[String, Unit]] = IO(for {
      _ <- fs.deleteDirectoryContent(path, except = Set(terraformDir))
      _ <- fs.createDirectory(path)
      _ <- fs.copyFileIfExists(existingLockFile, activeLockFile)
      _ <- fs.copyFileIfExists(existingStateFile, activeStateFile)
      _ <- fs.writeFile(digestFile, digest)
      _ <- config.config.generateFiles.toList.traverse_ { case (file, content) => fs.writeFile(pathMod.join(path, file), content) }
      _ <- if (config.config.module.nonEmpty) fs.writeFile(moduleFile, generateTerraformModule(path, config)) else Either.unit
      _ <- if (config.config.module.nonEmpty) fs.writeFile(outputFile, generateTerraformOutput(config)) else Either.unit
      _ <- if (config.config.providers.nonEmpty) fs.writeFile(providersFile, generateTerraformProviders(config)) else Either.unit
      _ <- if (config.config.backend.nonEmpty) fs.writeFile(backendFile, generateTerraformBackend(config)) else Either.unit
      _ <- config.config.copyFiles
             .flatMap(f => glob.sync(pathMod.resolve(config.dirPath, f)))
             .toSeq
             .traverse(f => fs.copyFile(f, pathMod.resolve(path, pathMod.basename(f))))
    } yield ())

    val initializeTerraform: IO[Either[String, Unit]] =
      config.config.backend.toOption.traverse(Backend.assureStateExists(_)) *>
        TerraformExecutor.terraformToOutput(appConfig, config.nameRelative, path, List("init") ++ appConfig.terraformInitArgs) *>
        IO(
          fs.writeFile(initSuccessFile, "") *>
            fs.copyFile(activeLockFile, existingLockFile) *>
            fs.copyFileIfExists(activeStateFile, existingStateFile),
        )

    val createProject     = shouldCreateProject.ifM(setupFiles, IO.pure(Either.unit))
    val initializeProject = shouldInitialize.ifM(initializeTerraform, IO.pure(Either.unit))

    createProject.flatMap(_.flatTraverse(_ => initializeProject))
  }
}
