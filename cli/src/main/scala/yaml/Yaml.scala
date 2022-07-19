package terraverse.yaml

import terraverse.native.JsNative
import typings.jsYaml.mod._
import typings.jsYaml.{jsYamlStrings, mod => jsYaml}

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.{|, UndefOr}

object JsYamlNode {
  class Params(val node: js.Any, val params: js.Dictionary[js.Any]) extends js.Object {
    override def toString: String = s"Params(${js.JSON.stringify(node)}, ${js.JSON.stringify(params)})"
  }
  class Code(val code: String, val nullable: Boolean)                                      extends js.Object {
    override def toString: String = s"Code(${code})"
  }
  class Overwrite(val node: js.Any)                                 extends js.Object {
    override def toString: String = s"Overwrite(${js.JSON.stringify(node)})"
  }
  class Merge(val nodes: js.Array[js.Any])                          extends js.Object {
    override def toString: String = s"Merge(${js.JSON.stringify(nodes)})"
  }
  class Required                                                    extends js.Object {
    override def toString: String = "Required"
  }

  def is(any: Any): Boolean =
    any.isInstanceOf[Params] ||
      any.isInstanceOf[Code] ||
      any.isInstanceOf[Overwrite] ||
      any.isInstanceOf[Merge] ||
      any.isInstanceOf[Required]
}

object Yaml {

  val tagNameTpl       = "!s"
  val tagNameJs        = "!js"
  val tagNameJsNullable = "!js?"
  val tagNameMerge     = "!merge"
  val tagNameOverwrite = "!overwrite"
  val tagNameRequired  = "!required"

  val resolveRequired: js.Function1[Any, Boolean] = { any =>
    !JsNative.isDefined(any)
  }

  val constructRequired: js.Function2[Any, UndefOr[String], Any] = { (_, _) =>
    new JsYamlNode.Required
  }

  val resolveOverwrite: js.Function1[Any, Boolean] = { any =>
    JsNative.isObject(any.asInstanceOf[js.Any])
  }

  val constructOverwrite: js.Function2[Any, UndefOr[String], Any] = { (any, _) =>
    new JsYamlNode.Overwrite(any.asInstanceOf[js.Any])
  }

  val resolveTpl: js.Function1[Any, Boolean] = { any =>
    JsNative.isString(any.asInstanceOf[js.Any])
  }

  val constructTpl: js.Function2[Any, UndefOr[String], Any] = { (any, _) =>
    new JsYamlNode.Code("`" + any.asInstanceOf[String] + "`", nullable = false)
  }

  val resolveJsNullable: js.Function1[Any, Boolean] = { any =>
    JsNative.isString(any.asInstanceOf[js.Any])
  }

  val constructJsNullable: js.Function2[Any, UndefOr[String], Any] = { (any, _) =>
    new JsYamlNode.Code(any.asInstanceOf[String], nullable = true)
  }

  val resolveJs: js.Function1[Any, Boolean] = { any =>
    JsNative.isString(any.asInstanceOf[js.Any])
  }

  val constructJs: js.Function2[Any, UndefOr[String], Any] = { (any, _) =>
    new JsYamlNode.Code(any.asInstanceOf[String], nullable = false)
  }

  val resolveFunction: js.Function1[Any, Boolean] = { any =>
    JsNative.isObject(any.asInstanceOf[js.Any])
  }

  val fullFunctionRegex                                          = "^!function\\(([;_a-zA-Z0-9]*)\\)$".r
  val constructFunction: js.Function2[Any, UndefOr[String], Any] = { (any, tagName) =>
    fullFunctionRegex.findAllMatchIn(tagName.get).toList match {
      case parameterGroup :: Nil =>
        val params = parameterGroup.group(1).split(";").filter(_.nonEmpty)
        new JsYamlNode.Params(
          new JsYamlNode.Code(s"(${params.mkString(", ")}) => { return __terraverse_result_constructor([${params.mkString(",")}]); }", nullable = false),
          js.Dictionary(
            "__terraverse_result_constructor" -> { (paramValues: js.Array[js.Any]) =>
              new JsYamlNode.Params(any.asInstanceOf[js.Any], params.zip(paramValues).toMap.toJSDictionary)
            },
          ),
        )
      case _                     => throw new Exception(s"Invalid function tag: ${tagName}")
    }
  }

  val resolveMerge: js.Function1[Any, Boolean] = {
    case arr: js.Array[js.Any @unchecked] => arr.nonEmpty
    case _                                => false
  }

  val constructMerge: js.Function2[Any, UndefOr[String], Any] = { (any, _) =>
    new JsYamlNode.Merge(any.asInstanceOf[js.Array[js.Any]])
  }

  val functionTypes = js
    .Array[jsYamlStrings.mapping | jsYamlStrings.sequence | jsYamlStrings.scalar](
      jsYamlStrings.mapping,
      jsYamlStrings.sequence,
      jsYamlStrings.scalar,
    )
    .map(kind =>
      new Type(
        "!function",
        TypeConstructorOptions()
          .setMulti(true)
          .setKind(kind)
          .setResolve(resolveFunction)
          .setConstruct(constructFunction),
      ),
    )

  val customTypes = js.Array(
    new Type(
      tagNameRequired,
      TypeConstructorOptions()
        .setKind(jsYamlStrings.scalar)
        .setResolve(resolveRequired)
        .setConstruct(constructRequired),
    ),
    new Type(
      tagNameOverwrite,
      TypeConstructorOptions()
        .setKind(jsYamlStrings.mapping)
        .setResolve(resolveOverwrite)
        .setConstruct(constructOverwrite),
    ),
    new Type(
      tagNameJsNullable,
      TypeConstructorOptions()
        .setKind(jsYamlStrings.scalar)
        .setResolve(resolveJsNullable)
        .setConstruct(constructJsNullable),
    ),
    new Type(
      tagNameJs,
      TypeConstructorOptions()
        .setKind(jsYamlStrings.scalar)
        .setResolve(resolveJs)
        .setConstruct(constructJs),
    ),
    new Type(
      tagNameTpl,
      TypeConstructorOptions()
        .setKind(jsYamlStrings.scalar)
        .setResolve(resolveTpl)
        .setConstruct(constructTpl),
    ),
    new Type(
      tagNameMerge,
      TypeConstructorOptions()
        .setKind(jsYamlStrings.sequence)
        .setResolve(resolveMerge)
        .setConstruct(constructMerge),
    ),
  )

  val yamlSchema = {
    val schemaDef = SchemaDefinition()
      .setExplicit(functionTypes ++ customTypes)

    JSON_SCHEMA.extend(schemaDef)
  }

  def loadYaml(content: String): js.Any =
    jsYaml.load(content, LoadOptions().setJson(true).setSchema(yamlSchema)).asInstanceOf[js.Any]

  def dumpYaml(value: js.Any): String =
    jsYaml.dump(
      value,
      DumpOptions()
        .setSortKeys(true)
        .setSchema(yamlSchema)
        .setReplacer((_, v) => if (JsNative.isFunction(v.asInstanceOf[js.Any])) "!function" else v),
    )
}
