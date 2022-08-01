package talpini.template

import cats.effect.IO
import cats.implicits._
import t.yaml._
import talpini.AppConfig
import talpini.config._
import talpini.native._
import talpini.proxy.Proxy
import typings.node.pathMod

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.annotation.JSImport

@JSImport("../../../../src/main/js/Eval.js", JSImport.Namespace)
@js.native
private object Eval extends js.Any {
  def evalBound(@annotation.unused data: js.Object): js.Function1[String, js.Any]                      = js.native
  def argumentsFunction(@annotation.unused proxy: js.Function1[js.Array[js.Any], js.Any]): js.Function = js.native
}

case class ConfigRunException(msg: String) extends Exception(s"Configuration Error: $msg")

object Templating {

  def replaceVariables(loadedConfig: LoadedConfigRaw, context: Context): IO[LoadedConfig] = {
    val configProxy: js.Any = {

      val dependencies: js.Any = context.dependencyOutputs.toJSDictionary

      val paths: js.Object = js.Dynamic.literal(
        file = loadedConfig.filePath,
        dir = loadedConfig.dirPath,
        root = loadedConfig.rootPath,
        terraform = loadedConfig.terraformPath,
        dir_from_root = pathMod.relative(loadedConfig.rootPath, loadedConfig.dirPath),
        dir_from_terraform = pathMod.relative(loadedConfig.terraformPath, loadedConfig.dirPath),
        root_from_dir = pathMod.relative(loadedConfig.dirPath, loadedConfig.rootPath),
        root_from_terraform = pathMod.relative(loadedConfig.terraformPath, loadedConfig.rootPath),
        terraform_from_dir = pathMod.relative(loadedConfig.dirPath, loadedConfig.terraformPath),
        terraform_from_root = pathMod.relative(loadedConfig.rootPath, loadedConfig.terraformPath),
      )

      val info: js.Object = js.Dynamic.literal(
        name = loadedConfig.name,
        version = AppConfig.version,
      )

      val api = js.Object.assign(
        js.Object(),
        TemplateApi,
        js.Dynamic.literal(
          dependencies = dependencies,
          paths = paths,
          info = info,
        ),
      )

      def jsReplacer(any: js.Any, inheritParams: Seq[js.Dictionary[js.Any]]): js.Any = any match {
        case s if JsNative.isString(s)                                   =>
          jsReplacer(new JsYamlNode.Code("`" + s.asInstanceOf[String] + "`", nullable = false), inheritParams)
        case o: JsYamlNode.Code                                   =>
          val evalScope = Proxy.lookup(inheritParams)
          val evalResult = Eval.evalBound(evalScope)(o.code)
          val result =
            if (JsNative.isPrimitive(evalResult))  evalResult
            else jsReplacer(evalResult, inheritParams)
          if (o.nullable || JsNative.isDefined(result)) result
          else new JsYamlNode.Required
        case o: JsYamlNode.Params                                 =>
          jsReplacer(o.node, inheritParams :+ o.params)
        case o: JsYamlNode.Merge                                  =>
          jsReplacer(o.nodes.map(jsReplacer(_, inheritParams)).reduce(JsNative.deepMerge(_, _)), inheritParams)
        case fun if JsNative.isFunction(fun)                      =>
          Eval.argumentsFunction { args =>
            jsReplacer(fun.asInstanceOf[js.Function].call(js.undefined, args.toSeq: _*), inheritParams)
          }
        case pro if JsNative.isPromise(pro)                       =>
          pro.asInstanceOf[js.Dynamic].`then`(jsReplacer(_, inheritParams))
        case arr if JsNative.isArray(arr)                         =>
          // arr.asInstanceOf[js.Array[js.Any]].map(jsReplacer(_, inheritParams))
          Proxy.lazyTransform(arr.asInstanceOf[js.Array[js.Any]], any => jsReplacer(any, inheritParams))
        case obj if JsNative.isObject(obj) && !JsYamlNode.is(obj) =>
          // obj.asInstanceOf[js.Dictionary[js.Any]].map { case (k, v) => k -> jsReplacer(v, inheritParams) }.toJSDictionary
          Proxy.lazyTransform(any.asInstanceOf[js.Object], any => jsReplacer(any, inheritParams))
        case any                                                  =>
          any
      }

      var data: js.Dictionary[js.Any] = null
      val configProxy                 = Proxy.lazyTransform(loadedConfig.config, any => jsReplacer(any, Seq(data))).asInstanceOf[js.Dictionary[js.Any]]
      data = Proxy.lookup(Seq(configProxy, api.asInstanceOf[js.Dictionary[js.Any]])).asInstanceOf[js.Dictionary[js.Any]]

      configProxy
    }

    def jsFlatten(path: List[String], any: js.Any): IO[js.Any] = any match {
      case o: JsYamlNode.Required         =>
        IO.raiseError(new ConfigRunException(s"Required field is missing at ${path.reverse.mkString(".")} (in ${loadedConfig.filePathRelative})."))
      case o if JsYamlNode.is(o)          =>
        IO.raiseError(new ConfigRunException(s"Unexpected control node at ${path.reverse.mkString(".")} (in ${loadedConfig.filePathRelative}): ${o}. This is a bug, please report it."))
      case pro if JsNative.isPromise(pro) =>
        IO.fromPromise(IO(pro.asInstanceOf[js.Promise[js.Any]])).flatMap(jsFlatten("<then>" :: path, _))
      case arr if JsNative.isArray(arr)   =>
        arr.asInstanceOf[js.Array[js.Any]].toSeq.zipWithIndex.traverse { case (v, i) => jsFlatten(s"[$i]" :: path, v) }.map(_.toJSArray)
      case obj if JsNative.isObject(obj)  =>
        obj.asInstanceOf[js.Dictionary[js.Any]].toSeq.traverse { case (k, v) => jsFlatten(k :: path, v).map(k -> _) }.map(_.toMap.toJSDictionary)
      case any                            =>
        IO.pure(any)
    }

    for {
      newConfigFlatten <- jsFlatten(Nil, configProxy)
      newConfig        <- IO.fromEither(
                            Config
                              .parse(newConfigFlatten)
                              .leftMap(errors => new Exception(s"Failed to parse config: ${errors.toList.mkString("\n-", "\n-", "\n")}")),
                          )
    } yield loadedConfig.copy(config = newConfig)
  }

}
