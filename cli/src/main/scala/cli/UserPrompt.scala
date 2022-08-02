package talpini.cli

import typings.readlineSync.{mod => readlineSync}

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.JSConverters.JSRichOption

object UserPrompt {

  private val qCache = mutable.HashMap.empty[String,String]
  def question(question: String): String = readlineSync.question(question)
  def questionCached(question: String) = qCache.getOrElseUpdate(question, this.question(question))

  private val qIntCache = mutable.HashMap.empty[String,js.UndefOr[Int]]
  def questionInt(question: String) = readlineSync.question(question).toIntOption.orUndefined
  def questionIntCached(question: String) = qIntCache.getOrElseUpdate(question, this.questionInt(question))

  private val chooseCache = mutable.HashMap.empty[String,js.UndefOr[String]]
  def choose(question: String, choices: js.Array[String]): js.UndefOr[String] = choices(readlineSync.keyInSelect(choices, question).toInt)
  def chooseCached(question: String, choices: js.Array[String]) = chooseCache.getOrElseUpdate(question + ":" + js.JSON.stringify(choices), this.choose(question, choices))

  private val confirmCache = mutable.HashMap.empty[String,Boolean]
  def confirm(question: String): Boolean = readlineSync.keyInYNStrict(question) == true
  def confirmCached(question: String) = confirmCache.getOrElseUpdate(question, this.confirm(question))
}
