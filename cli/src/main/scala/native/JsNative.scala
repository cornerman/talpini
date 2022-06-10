package terraverse.native

import terraverse.yaml.JsYamlNode

import scala.scalajs.js
import scala.scalajs.js.JSConverters._

object JsNative {

  def isDefined(o: Any): Boolean  = o != null && o != (js.undefined: js.Any)
  def isObject(o: Any): Boolean   = isDefined(o) && js.typeOf(o) == "object" && !isArray(o) && !isPromise(o)
  def isPromise(o: Any): Boolean  = isDefined(o) && js.typeOf(o) == "object" && js.typeOf(o.asInstanceOf[js.Dynamic].`then`) == "function"
  def isFunction(o: Any): Boolean = isDefined(o) && js.typeOf(o) == "function"
  def isArray(o: Any): Boolean    = isDefined(o) && js.Array.isArray(o)
  def isString(o: Any): Boolean   = isDefined(o) && js.typeOf(o) == "string"
  def isBoolean(o: Any): Boolean  = isDefined(o) && js.typeOf(o) == "boolean"
  def isNumber(o: Any): Boolean   = isDefined(o) && js.typeOf(o) == "number"

  def deepMergeObjects(dictA: js.Dictionary[js.Any], dictB: js.Dictionary[js.Any]): js.Dictionary[js.Any] = {
    val allKeys = dictA.keySet ++ dictB.keySet

    val newEntries = allKeys.map { key =>
      val valueA = dictA.get(key)
      val valueB = dictB.get(key)

      key -> deepMerge(valueA.orUndefined, valueB.orUndefined)
    }

    js.Dictionary[js.Any](newEntries.toSeq: _*)
  }

  def deepMerge(a: js.Any, b: js.Any): js.Any = (a, b) match {
    case (_, b: JsYamlNode.Overwrite)                => b.node
    case (_, b: JsYamlNode.Merge)                    => b
    case (_, b: JsYamlNode.Code)                     => b
    case (_, b: JsYamlNode.Params)                   => b
    case (a, b: JsYamlNode.Required) if isDefined(a) => a

    case (a: js.Array[js.Any], b: js.Array[js.Any]) =>
      a.concat(b)

    case (a: js.Object, b: js.Object) =>
      val dictA = a.asInstanceOf[js.Dictionary[js.Any]]
      val dictB = b.asInstanceOf[js.Dictionary[js.Any]]
      deepMergeObjects(dictA, dictB)

    case (_, b) if isDefined(b) => b

    case (a, _) => a
  }
}
