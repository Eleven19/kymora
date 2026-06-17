package io.eleven19.kymora.workflow.cli

import io.eleven19.kymora.workflow.*
import kyo.*

/** Typeclass for parsing CLI argument tokens into a user-defined `Args` type.
  *
  * The engine summons one of these via the implicit constraint on
  * [[Command.cli]]; whoever defines the `Args` shape provides the parser.
  *
  * Two construction paths are provided today:
  *   - The `unit` instance below covers `Command.cli[Unit, A]` (no args).
  *   - `CommandArgs.from(parse, usage)` is a manual escape hatch for ad-hoc
  *     parsers — used by tests and by hand-rolled CLI surfaces.
  *
  * A mainargs-backed automatic derivation (Task 51) is deferred: it added too
  * much surface for this batch. Users wanting automatic derivation can wrap a
  * `mainargs.ParserForClass` inside `from` themselves in the meantime.
  */
trait CommandArgs[Args]:
  /** Parse the raw token list into an `Args` or a [[CliParseError]]. */
  def parse(tokens: Seq[String]): Result[CliParseError, Args]

  /** Renderable usage banner shown in error messages. */
  def usage: String
end CommandArgs

object CommandArgs:

  /** The trivial parser: any token sequence succeeds, value is `()`. Used to
    * model "this command takes no CLI args" without forcing a derivation.
    */
  given unit: CommandArgs[Unit] = new CommandArgs[Unit]:
    def parse(tokens: Seq[String]): Result[CliParseError, Unit] = Result.succeed(())
    def usage: String                                           = ""

  /** Build a `CommandArgs[A]` from a parse function and a usage string. */
  def from[A](
      parse: Seq[String] => Result[CliParseError, A],
      usage: String,
  ): CommandArgs[A] =
    val p = parse
    val u = usage
    new CommandArgs[A]:
      def parse(tokens: Seq[String]): Result[CliParseError, A] = p(tokens)
      def usage: String                                        = u
  end from
end CommandArgs
