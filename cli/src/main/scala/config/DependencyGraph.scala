package talpini.config

import talpini.AppConfig
import typings.node.pathMod

case class DependencyGraphEntry(loadedConfig: LoadedConfigRaw, hasDependee: Boolean)

case class DependencyGraph(entries: Seq[Seq[DependencyGraphEntry]])

object DependencyGraph {

  def resolve(appConfig: AppConfig, configs: Seq[LoadedConfigRaw]): Either[String, DependencyGraph] = {

    val distinctConfigs = configs.distinct

    val todo      = collection.mutable.Queue.from(distinctConfigs)
    val todoPaths = collection.mutable.HashSet.from[String](distinctConfigs.map(_.filePath))
    val seenPaths = collection.mutable.HashSet[String]()

    val marked = collection.mutable.HashSet[LoadedConfigRaw]()

    val batches          = collection.mutable.ArrayBuffer[collection.mutable.ArrayBuffer[LoadedConfigRaw]]()
    def newBatch(): Unit = {
      marked.foreach(todo.enqueue(_))
      marked.clear()
      if (batches.lastOption.forall(_.nonEmpty)) {
        batches.lastOption.foreach(_.foreach(c => seenPaths += c.filePath))
        batches += collection.mutable.ArrayBuffer[LoadedConfigRaw]()
      }
    }
    newBatch()

    while (todo.nonEmpty) {
      val config = todo.dequeue()

      if (!seenPaths.contains(config.filePath)) {
        val depRefs              = config.config.dependencies.toList.flatMap(_.values.map(ref => pathMod.resolve(pathMod.dirname(config.filePath), ref)))
        val dependenciesResolved = depRefs.forall(seenPaths.contains)
        if (dependenciesResolved) {
          batches.last += config
        }
        else {
          depRefs.foreach { depRef =>
            if (!todoPaths.contains(depRef)) {
              todoPaths += depRef
              ConfigReader.readConfigs(appConfig, depRef) match {
                case Left(error)                         =>
                  return Left(s"Error resolving dependency '${depRef}': ${error}")
                case Right(configs) if configs.size == 0 =>
                  return Left(s"Cannot find dependency '${depRef}''")
                case Right(configs) if configs.size > 1  =>
                  return Left(s"Found multiple files for dependency '${depRef}'")
                case Right(configs)                      =>
                  configs.foreach(todo.enqueue(_))
              }
            }
          }

          marked += config
        }
      }

      if (todo.isEmpty) newBatch()
    }

    val batchesResult = batches.map(_.toSeq).toSeq.filter(_.nonEmpty)

    if (marked.isEmpty) {
      val dependees: Set[String] =
        batchesResult
          .flatten
          .flatMap(c => c.config.dependencies.toList.flatMap(_.values.map(ref => pathMod.resolve(pathMod.dirname(c.filePath), ref))))
          .toSet

      val entries = batchesResult.map(_.map(c => DependencyGraphEntry(c, dependees.contains(c.filePath))))
      Right(DependencyGraph(entries))
    }
    else Left(s"Error resolving dependencies: ${marked.map(_.filePath).mkString("\n- ", "\n- ", "")}")
  }
}
