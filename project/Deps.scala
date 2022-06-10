import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbt._

object Deps {
  import Def.{setting => s}

  val scalatest = s("org.scalatest" %%% "scalatest" % "3.2.12")

  val cats = new {
    val core   = s("org.typelevel" %%% "cats-core" % "2.7.0")
    val effect = s("org.typelevel" %%% "cats-effect" % "3.3.12")
  }

  val scalaJS = new {
    val secureRandom = s("org.scala-js" %%% "scalajs-java-securerandom" % "1.0.0")
  }

  val pprint = s("com.lihaoyi" %%% "pprint" % "0.7.0")
}
