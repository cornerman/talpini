package talpini.cloud

import cats.effect.IO
import cats.implicits._
import talpini.cloud.aws.AWS
import talpini.config.ConfigBackend
import talpini.logging.Logger

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

object Backend {
  private val seenBackends = mutable.HashMap.empty[String, Future[Unit]]

  private def assureStateExistsImpl(config: ConfigBackend): IO[Unit] = config match {
    case s3 if s3.tpe == ConfigBackend.S3.tpeName => AWS.createS3State(s3.asInstanceOf[ConfigBackend.S3])
    case _                                        => IO.unit
  }

  private def configToCacheEntry(config: ConfigBackend): String = {
    val copy = js.Object.assign(js.Object(), config)
    copy.asInstanceOf[js.Dictionary[js.Any]].remove("key")
    js.JSON.stringify(copy)
  }

  def assureStateExists(config: ConfigBackend): IO[Unit] = {
    def configId = configToCacheEntry(config)

    val action = for {
      ref <- IO(Promise[Unit]())
        _ <- IO(Logger.info("Checking remote backend"))
        _ <- IO(seenBackends.update(configId, ref.future))
        _ <- assureStateExistsImpl(config)
        _ <- IO(ref.trySuccess(()))
    } yield ()

    for {
      existing <- IO(seenBackends.get(configId))
      _           <- existing.fold(action)(f => IO.fromFuture(IO(f)))
    } yield ()
  }
}
