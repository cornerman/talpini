package native

import org.scalablytyped.runtime.StringDictionary
import typings.node.NodeJS.ReadableStream
import typings.node.{httpMod, httpsMod}

import scala.scalajs.js

object JsHttp {
  val getText: js.Function2[String, js.UndefOr[httpMod.OutgoingHttpHeaders], js.Promise[String]] = { (url, headers) =>
    new js.Promise[String]({ (resolve, reject) =>
      val req = httpsMod.get(
        url,
        httpsMod.RequestOptions().setHeaders(headers.getOrElse(StringDictionary.empty[js.UndefOr[httpMod.OutgoingHttpHeader]])),
        { msg =>
          val stream = msg.asInstanceOf[ReadableStream]

          stream.on(
            "data",
            { result =>
              val _ = resolve(result.asInstanceOf[js.Dynamic].applyDynamic("toString")("utf-8").asInstanceOf[String])
            },
          )

          stream.on(
            "error",
            { error =>
              val _ = reject(error.asInstanceOf[js.Any])
            },
          )

          ()
        },
      )

      req.asInstanceOf[js.Dynamic].end()
    })
  }
}
