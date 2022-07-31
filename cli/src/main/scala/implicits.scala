package talpini

import cats.Traverse
import cats.effect.IO
import cats.implicits._

object implicits {
  implicit class ParTraverseOps[T[_]: Traverse, A](ta: T[A]) {
    def parTraverseIf[B](condition: Boolean)(f: A => IO[B]): IO[T[B]] =
      if (condition) ta.parTraverse(f) else ta.traverse(f)
  }

}
