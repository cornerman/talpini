package talpini.cli

import cats.Monad
import cats.implicits._
import typings.node.processMod

sealed trait CliTail[F[_], T]
object CliTail {
  case class Args[F[_], T](description: String, fold: (T, List[String]) => F[T])                                 extends CliTail[F, T]
  case class ArgsWithSeparator[F[_], T](separator: String, description: String, fold: (T, List[String]) => F[T]) extends CliTail[F, T]
  case class Empty[F[_], T](default: F[T])                                                                       extends CliTail[F, T]
}

case class OptId private (options: List[String])
object OptId {
  def short(s: String): OptId                   = OptId(s"-${s}" :: Nil)
  def long(s: String): OptId                    = OptId(s"--${s}" :: Nil)
  def apply(short: String, long: String): OptId = OptId(OptId.short(short).options ++ OptId.long(long).options)
  def raw(options: List[String]): OptId         = OptId(options)
}

sealed trait CliOpt[F[_], T]
object CliOpt {
  case class Flag[F[_], T](
    id: OptId,
    description: String,
    fold: T => F[T],
  ) extends CliOpt[F, T]
  case class Arg[F[_], T](
    id: OptId,
    description: String,
    fold: (T, String) => F[T],
  ) extends CliOpt[F, T]
}

case class Command[F[_]: Monad, T](
  name: String,
  tail: CliTail[F, T],
  options: List[CliOpt[F, T]],
) {
  def help: String = {
    def helpOpt(opt: CliOpt[F, T]): String = opt match {
      case CliOpt.Flag(id, desc, _) => s"${id.options.mkString("|")} - $desc"
      case CliOpt.Arg(id, desc, _)  => s"${id.options.mkString("|")} <arg> - $desc"
    }

    val head = tail match {
      case CliTail.Args(_, _)                         => s"Usage: ${name} [<options>] [<command>]\n"
      case CliTail.ArgsWithSeparator(separator, _, _) => s"Usage: ${name} [<options>] ${separator} [<command>]\n"
      case CliTail.Empty(_)                           => s"Usage: ${name} [<options>]\n"
    }

    val commandHelp = tail match {
      case CliTail.Args(description, _)                 => s"\nCommand: ${description}\n"
      case CliTail.ArgsWithSeparator(_, description, _) => s"\nCommand: ${description}\n"
      case CliTail.Empty(_)                             => ""
    }

    val optionHelp = options match {
      case options if options.isEmpty => ""
      case options                    => options.map(helpOpt).mkString("\nOptions:\n\t", "\n\t", "\n")
    }

    head + commandHelp + optionHelp
  }

  // We parse environment variable to cli options in the form: <NAME>_CLI_<long_option>
  // Example for flags: `<NAME>_CLI_RUN_ALL="true"`              translates to `--run-all`
  // Example for args:  `<NAME>_CLI_TARGET="vpc.t.yaml"` translates to `--target vpc.t.yaml`
  def parseFromEnv(seed: T): F[T] = {
    def envSanitizer(s: String) = s.replaceAll("-", "_").toUpperCase

    def optIdToEnvIds(optId: OptId) = optId.options.collect { case s"--$id" => envSanitizer(id) }

    val sanitizedName = envSanitizer(name)
    val env           = processMod.env.toMap.collect { case (s"${`sanitizedName`}_CLI_$k", v) => v.toOption.map(k -> _) }.flatten.toMap

    options.foldM(seed) {
      case (current, CliOpt.Flag(id, _, fold)) =>
        optIdToEnvIds(id).collectFirstSome(env.get).flatMap(_.toBooleanOption).fold(Monad[F].pure(current))(_ => fold(current))
      case (current, CliOpt.Arg(id, _, fold))  =>
        optIdToEnvIds(id).collectFirstSome(env.get).fold(Monad[F].pure(current))(fold(current, _))
    }
  }

  def parse(seed: T, args: Array[String]): F[T] = {
    var i             = 0
    var current: F[T] = Monad[F].pure(seed)

    while (i < args.length) {
      val arg = args(i)

      val iBefore = i
      options.foreach {
        case CliOpt.Flag(id, _, fold) if id.options.contains(arg)                       =>
          current = current.flatMap(fold(_))
          i += 1
        case CliOpt.Arg(id, _, fold) if args.length > i + 1 && id.options.contains(arg) =>
          current = current.flatMap(fold(_, args(i + 1)))
          i += 2
        case _                                                                          =>
          ()
      }

      val noMatch = i == iBefore

      if (noMatch) {
        tail match {
          case CliTail.Args(_, fold)                                             =>
            current = current.flatMap(fold(_, args.drop(i).toList))
            i = args.length
          case CliTail.ArgsWithSeparator(separator, _, fold) if arg == separator =>
            current = current.flatMap(fold(_, args.drop(i + 1).toList))
            i = args.length
          case CliTail.Empty(value) if i == args.length - 1                      =>
            current = value
            i = args.length
          case _                                                                 =>
            // TODO: error?
            i = args.length
        }
      }
    }

    current
  }
}
