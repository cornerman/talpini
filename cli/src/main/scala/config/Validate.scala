package talpini.config

import cats.data.EitherNec
import cats.implicits._
import talpini.native.JsNative

import scala.scalajs.js

trait Validate  {
  def fieldFlat(field: String)(f: js.Any => EitherNec[String, Any]): EitherNec[String, Unit]
  def fieldOption(field: String)(f: js.Any => Option[String]): EitherNec[String, Unit]
  def field(field: String)(f: js.Any => Boolean): EitherNec[String, Unit]
}
object Validate {
  def apply(o: js.Any): Validate = new Validate {
    def fieldFlat(field: String)(f: js.Any => EitherNec[String, Any]): EitherNec[String, Unit] =
      if (!JsNative.isObject(o)) {
        s"Expected object for backend, got: ${js.typeOf(o)}".leftNec
      }
      else {
        val member = o.asInstanceOf[js.Dynamic].selectDynamic(field)
        f(member).void
      }

    def fieldOption(field: String)(f: js.Any => Option[String]): EitherNec[String, Unit] =
      fieldFlat(field) { member =>
        f(member).toLeftNec(())
      }

    def field(field: String)(f: js.Any => Boolean): EitherNec[String, Unit] =
      fieldFlat(field) { member =>
        if (f(member)) ().rightNec
        else s"Unexpected value at field ${field}: ${js.typeOf(member)}".leftNec
      }
  }
}
