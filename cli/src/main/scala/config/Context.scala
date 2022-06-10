package terraverse.config

import scala.scalajs.js

case class ContextAws(
  accountId: String,
  region: String,
)

case class Context(
  dependencyOutputs: Map[String, js.Any],
  aws: Option[ContextAws],
)
