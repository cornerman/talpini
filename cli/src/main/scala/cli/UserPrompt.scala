package talpini.cli

import typings.readlineSync.{mod => readlineSync}

import scala.collection.mutable

object UserPrompt {
  private val questionCache = mutable.HashMap.empty[String,String]

  def question(question: String): String = readlineSync.question(question)

  def questionCached(question: String) = questionCache.getOrElseUpdate(question, this.question(question))

  def confirmIf(when: Boolean)(question: String): Boolean =
    !when || readlineSync.keyInYNStrict(question) == true

  def confirmAlways(question: String): Boolean = confirmIf(true)(question)
}
