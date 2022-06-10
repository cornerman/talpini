package terraverse.native

import cats.implicits._
import terraverse.logging.Logger
import typings.node.bufferMod.global.BufferEncoding
import typings.node.{fsMod, pathMod}

class FileSystem(readPath: String, writePath: String) {
  private val readAbsolutePath  = pathMod.resolve(readPath)
  private val writeAbsolutePath = pathMod.resolve(writePath)

  private def canReadFilePath(path: String): Boolean = {
    val absolutePath = pathMod.resolve(path)

    absolutePath.startsWith(readAbsolutePath)
  }

  private def canWriteFilePath(path: String): Boolean = {
    val absolutePath = pathMod.resolve(path)

    val canWrite = absolutePath.startsWith(writeAbsolutePath) && (
      absolutePath.contains(s"${pathMod.sep}.terraverse${pathMod.sep}") ||
        absolutePath.endsWith(s"${pathMod.sep}.terraverse")
    )

    // we allow ourselves to overwrite terraform state/lock files outside of the write dir and write back to the read dir
    val canWriteBackFiles = absolutePath.startsWith(readAbsolutePath) && (
      absolutePath.endsWith(".terraform.lock.hcl") ||
        absolutePath.endsWith(".tfstate")
    )

    canWrite || canWriteBackFiles
  }

  private def withCanReadFilePath[T](path: String)(body: => Either[String, T]): Either[String, T] =
    if (canReadFilePath(path)) body
    else Left(s"Supposed to read from an unexpected file path: ${path}. Will cancel. This is a bug.")

  private def withCanWriteFilePath[T](path: String)(body: => Either[String, T]): Either[String, T] =
    if (canWriteFilePath(path)) body
    else Left(s"Supposed to write to an unexpected file path: ${path}. Will cancel. This is a bug.")

  def createDirectory(path: String): Either[String, Unit] = withCanReadFilePath(path) {
    if (!fsMod.existsSync(path)) {
      Logger.trace(s"Creating directory ${pathMod.relative(".", path)}")
      Either.catchNonFatal(fsMod.mkdirSync(path, fsMod.MakeDirectoryOptions().setRecursive(true))).left.map(e => s"Cannot create directory $path: $e").void
    }
    else Either.unit
  }

  def deleteFile(path: String, recursive: Boolean = false): Either[String, Unit] = withCanWriteFilePath(path) {
    if (fsMod.existsSync(path)) {
      Logger.trace(s"Deleting cache file: ${pathMod.relative(".", path)}")
      Either.catchNonFatal(fsMod.rmSync(path, fsMod.RmOptions().setRecursive(recursive))).left.map(e => s"Error deleting file $path: $e")
    }
    else Either.unit
  }

  // delete everything except certain filenames
  def deleteDirectoryContent(path: String, except: Set[String]): Either[String, Unit] = withCanWriteFilePath(path) {
    if (fsMod.existsSync(path)) {
      val entries       = fsMod.readdirSync(path)
      val deleteEntries = entries.filterNot(except.contains)
      deleteEntries.toSeq.traverse_ { entry =>
        val fullPath = pathMod.resolve(path, entry)
        deleteFile(fullPath, recursive = true)
      }
    }
    else Either.unit
  }

  def copyFileIfExists(source: String, target: String): Either[String, Unit] =
    if (fsMod.existsSync(source)) copyFile(source, target) else Either.unit

  def copyFile(source: String, target: String): Either[String, Unit] = withCanReadFilePath(source)(withCanWriteFilePath(target) {
    Logger.trace(s"Copying cache file: ${pathMod.relative(".", source)} -> ${pathMod.relative(".", target)}")
    Either.catchNonFatal(fsMod.cpSync(source, target, fsMod.CopyOptions().setRecursive(true))).left.map(e => s"Cannot copy file $source -> $target: $e")
  })

  def writeFile(path: String, content: String): Either[String, Unit] = withCanWriteFilePath(path) {
    Logger.trace(s"Writing cache file: ${pathMod.relative(".", path)}")
    Either.catchNonFatal(fsMod.writeFileSync(path, content)).left.map(e => s"Cannot write file $path: $e")
  }

  def readFile(path: String): Either[String, String] = withCanReadFilePath(path) {
    Logger.trace(s"Reading file ${pathMod.relative(".", path)}")
    Either.catchNonFatal(fsMod.readFileSync(path, BufferEncoding.utf8)).left.map(e => s"Cannot read file $path: $e")
  }
}
