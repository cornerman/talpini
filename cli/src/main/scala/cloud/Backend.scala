package talpini.cloud

import cats.effect.IO
import cats.implicits._
import talpini.cloud.aws.AWS
import talpini.config.ConfigBackend
import talpini.logging.Logger

import scala.collection.mutable

object Backend {
  private val seenBackends = mutable.HashSet.empty[ConfigBackend]

  private def assureStateExistsImpl(config: ConfigBackend): IO[Unit] = config match {
    case s3 if s3.tpe == ConfigBackend.S3.tpeName => AWS.createS3State(s3.asInstanceOf[ConfigBackend.S3])
    case _                                        => IO.unit
  }

  def assureStateExists(config: ConfigBackend): IO[Unit] = {
    val action =
      IO(Logger.info("Checking remote backend")) *>
        IO(seenBackends.add(config)) *>
        assureStateExistsImpl(config)

    for {
      alreadySeen <- IO(seenBackends.contains(config))
      _           <- action.whenA(!alreadySeen)
    } yield ()
  }
}
