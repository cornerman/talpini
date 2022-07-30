package talpini.config

import cats.implicits._
import talpini.AppConfig
import talpini.native.JsNative
import t.yaml.Yaml
import typings.glob.{mod => glob}
import typings.node.bufferMod.global.BufferEncoding
import typings.node.{fsMod, pathMod}

import scala.scalajs.js

object ConfigReader {
  private def isFile(file: String): Boolean = fsMod.existsSync(file) && fsMod.lstatSync(file).asInstanceOf[fsMod.Stats].isFile()

  private def isHidden(x: String): Boolean = pathMod.basename(x).matches("^\\..+")

  private def isTargetFile(x: String): Boolean = isFile(x) && !isHidden(x) && (x.endsWith(".t.yml") || x.endsWith(".t.yaml"))

  @annotation.tailrec
  def findFilesInDirAndUp(dir: String, filenames: List[String], accum: List[String] = List.empty): List[String] =
    if (!fsMod.existsSync(dir)) accum
    else {
      val targetStat = fsMod.lstatSync(dir).asInstanceOf[fsMod.Stats]
      // we need to make sure that we return filenames in the order they are passed in! Because they are used
      // to load configuration and should have a deterministic order of overwriting.
      val files      = if (targetStat.isDirectory()) filenames.flatMap(f => glob.sync(pathMod.resolve(dir, f))).filter(isFile(_)) else Nil
      val upDir      = pathMod.resolve(dir, "..")
      if (upDir == dir) accum
      else findFilesInDirAndUp(upDir, filenames, files ++ accum)
    }

  def findFilesInDirAndDown(dir: String): List[String] =
    if (!fsMod.existsSync(dir) || isHidden(dir)) Nil
    else {
      val targetStat = fsMod.lstatSync(dir).asInstanceOf[fsMod.Stats]
      if (targetStat.isDirectory()) fsMod.readdirSync(dir).map(pathMod.resolve(dir, _)).flatMap(findFilesInDirAndDown(_)).toList
      else List(dir)
    }

  def findFilesInTarget(target: String): List[String] = glob.sync(target).toList.flatMap { target =>
    findFilesInDirAndDown(target).filter(isTargetFile(_))
  }

  def findCompanionFiles(appConfig: AppConfig, file: String): Either[String, List[String]] =
    if (!fsMod.existsSync(file)) Left(s"File '${file}' does not exist")
    else {
      val baseFiles = findFilesInDirAndUp(file, appConfig.autoIncludes)
      Right(baseFiles)
    }

  def decodeJsToConfig(o: js.Any): Either[String, ConfigRaw] =
    ConfigRaw.parse(o).left.map(errors => s"Error parsing Config: ${errors.toList.mkString(", ")}")

  def readConfigsFromIncludes(appConfig: AppConfig, loadedConfig: LoadedConfigRaw): Either[String, List[LoadedConfigRaw]] =
    loadedConfig.config.includes.toList.flatten
      .flatMap(f => glob.sync(pathMod.resolve(loadedConfig.dirPath, f)))
      .flatTraverse { include =>
        readConfigs(appConfig, pathMod.resolve(pathMod.dirname(loadedConfig.filePath), include))
      }

  def readConfigFromYaml(file: String): Either[String, LoadedConfigRaw] =
    for {
      content     <- Either.catchNonFatal(fsMod.readFileSync(file, BufferEncoding.utf8)).left.map(e => s"Cannot read file $file: $e")
      configJsRaw <-
        Either
          .catchNonFatal(Yaml.loadYaml(content))
          .left
          .map(e => s"Cannot load yaml from file $file: $e")
      configJs     = configJsRaw.asInstanceOf[js.UndefOr[js.Any]].getOrElse(js.Object.apply())
      config      <- decodeJsToConfig(configJs)
    } yield LoadedConfigType(pathMod.resolve(file), pathMod.dirname(file), config)

  def readConfigFromYamlWithIncludes(appConfig: AppConfig, file: String): Either[String, List[LoadedConfigRaw]] =
    for {
      config         <- readConfigFromYaml(file)
      includeConfigs <- readConfigsFromIncludes(appConfig, config)
    } yield includeConfigs :+ config

  def readAllConfigsForTargetFile(appConfig: AppConfig, file: String): Either[String, List[LoadedConfigRaw]] = for {
    companionFiles   <- findCompanionFiles(appConfig, file)
    companionConfigs <- companionFiles.toSeq.flatTraverse(readConfigFromYamlWithIncludes(appConfig, _))
    configs          <- readConfigFromYamlWithIncludes(appConfig, file)
  } yield (companionConfigs ++ configs).map(c => companionFiles.headOption.fold(c)(f => c.copy(rootPath = pathMod.dirname(f))))

  def readConfigFromFile(appConfig: AppConfig, file: String): Either[String, LoadedConfigRaw] = for {
    allConfigs <- readAllConfigsForTargetFile(appConfig, file)
    configJs    = allConfigs.map(_.config).foldLeft[js.Any](js.Object())(JsNative.deepMerge)
    config     <- decodeJsToConfig(configJs)
  } yield LoadedConfigType(pathMod.resolve(file), allConfigs.lastOption.fold(pathMod.dirname(file))(c => c.rootPath), config)

  def readConfigs(appConfig: AppConfig, target: String): Either[String, List[LoadedConfigRaw]] =
    findFilesInTarget(target).traverse(readConfigFromFile(appConfig, _))
}
