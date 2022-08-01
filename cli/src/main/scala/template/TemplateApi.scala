package talpini.template

import native.JsHttp
import t.yaml.Yaml
import talpini.cli.UserPrompt
import talpini.native.JsNative
import typings.node.bufferMod.global.BufferEncoding
import typings.node.{fsMod, processMod}

import scala.scalajs.js
import scala.scalajs.js.|

object TemplateApi extends js.Object {

  val file: js.Object = js.Dynamic.literal(
    readText = (file: String) => fsMod.readFileSync(file, BufferEncoding.utf8),
    exists = (file: String) => fsMod.existsSync(file),
  )

  val http: js.Object = js.Dynamic.literal(
    getText = JsHttp.getText,
  )

  val prompt: js.Object = js.Dynamic.literal(
    question = (s => UserPrompt.questionCached("\n" + s)): js.Function1[String, String],
  )

  val yaml: js.Function1[js.Array[String] | String, js.Any] = { arg =>
    // TODO: wtf?
    val input = if (JsNative.isArray(arg)) arg.asInstanceOf[js.Array[String]].mkString("\n") else arg.asInstanceOf[String]
    Yaml.loadYaml(input)
  }

  val json: js.Function1[js.Array[String] | String, js.Any] = { arg =>
    // TODO: wtf?
    val input = if (JsNative.isArray(arg)) arg.asInstanceOf[js.Array[String]].mkString("\n") else arg.asInstanceOf[String]
    js.JSON.parse(input)
  }

  val env: js.Object = processMod.env

  // val require: NodeRequire = typings.node.moduleMod.createRequire(loadedConfig.dirPath)
}
