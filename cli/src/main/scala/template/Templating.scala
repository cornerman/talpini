package terraverse.template

import cats.effect.IO
import cats.implicits._
import native.JsHttp
import terraverse.AppConfig
import terraverse.config._
import terraverse.native._
import terraverse.proxy.Proxy
import terraverse.yaml._
import typings.node.bufferMod.global.BufferEncoding
import typings.node.{fsMod, pathMod, processMod}

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.|

@JSImport("../../../../src/main/js/Eval.js", JSImport.Namespace)
@js.native
private object Eval extends js.Any {
  def evalBound(@annotation.unused data: js.Object): js.Function1[String, js.Any]                      = js.native
  def argumentsFunction(@annotation.unused proxy: js.Function1[js.Array[js.Any], js.Any]): js.Function = js.native
}

object Templating {

  def replaceVariables(loadedConfig: LoadedConfigRaw, context: Context): IO[LoadedConfig] = {
    val configProxy: js.Any = {

      val dependencies: js.Any = context.dependencyOutputs.toJSDictionary

      val aws: js.Any = context.aws
        .map(aws =>
          js.Dynamic.literal(
            account = aws.accountId,
            region = aws.region,
          ),
        )
        .orUndefined

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
        TemplateApi,
        js.Dynamic.literal(
          aws = aws,
          dependencies = dependencies,
          paths = paths,
          info = info,
        ),
      )

      def jsReplacer(any: js.Any, inheritParams: Seq[js.Dictionary[js.Any]]): js.Any = any match {
        case o: JsYamlNode.Code                                   =>
          val evalScope = Proxy.lookup(inheritParams)
          jsReplacer(Eval.evalBound(evalScope)(o.code), inheritParams)
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
        IO.raiseError(new Exception(s"Required field is missing at ${path.reverse.mkString(".")}. Will stop."))
      case o if JsYamlNode.is(o)          =>
        IO.raiseError(new Exception(s"Unexpected control node at ${path.reverse.mkString(".")}: ${o}. Will stop."))
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
