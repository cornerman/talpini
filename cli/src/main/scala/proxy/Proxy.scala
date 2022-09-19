package talpini.proxy

import t.yaml.JsYamlNode
import talpini.native.JsNative

import scala.scalajs.js
import scala.scalajs.js.PropertyDescriptor

object Proxy {
  def lookup(lookups: Seq[js.Dictionary[js.Any]]): js.Object = {
    val proxy = js.Object()

    val allKeys = lookups.flatMap(_.keys).distinct

    allKeys.foreach { property =>
      js.Object.defineProperty(
        proxy,
        property,
        new PropertyDescriptor {
          enumerable = true
          configurable = true
          get = { () =>
            val allFound = lookups.flatMap(_.get(property))
            val found    = allFound.lastOption
            found match {
              case Some(foundV) if JsNative.isObject(foundV) && !JsYamlNode.is(foundV) =>
                Proxy.lookup(allFound.filter(JsNative.isObject(_)).asInstanceOf[Seq[js.Dictionary[js.Any]]])
              case Some(foundV)                                                        =>
                foundV
              case None                                                                =>
                js.undefined
            }
          }: js.Function0[js.Any]
        },
      )
    }

    proxy
  }

  def lazyTransform[T](data: js.Object, transform: (String, js.Any) => js.Any): js.Any = {
    val proxy: js.Object = if (JsNative.isArray(data)) js.Array[js.Any]() else js.Object()

    val resolved = js.Dictionary.empty[js.Any]

    val allEntries = js.Object.entries(data).map(t => t._1 -> t._2)

    allEntries.foreach { case (k, v) =>
      js.Object.defineProperty(
        proxy,
        k,
        new PropertyDescriptor {
          enumerable = true
          configurable = true
          get = { () =>
            resolved.get(k) match {
              case Some(resolvedV) => resolvedV
              case None            =>
                val result = transform(k, v.asInstanceOf[js.Any])
                resolved.put(k, result)
                result
            }
          }: js.Function0[Any]
        },
      )

    }
    proxy
  }
}
