package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.cli.CommandArgs
import kyo.*

/** Always-runs task kind: the command's own output is never memoized, but its
  * dependencies still cache normally. Used to model invocable entry points
  * (e.g. CLI subcommands) on top of the same Task DAG.
  *
  * The actual `Command` class is defined inside [[Task]] so it can extend the
  * sealed `Task` trait. This file exposes the top-level `Command` alias and
  * the `Command.init` smart constructors (non-CLI overloads, arities 0..2 for
  * now — CLI-aware `Command.cli` and higher arities land in Phase 13).
  */
type Command[A] = Task.Command[A]

object Command:
  // Leaf (no deps)
  def init[A](id: String, version: TaskVersion)(
      value: => A,
  )(using scope: TaskScope): Command[A] =
    new Task.Command[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Nil,
      (_, _) => value,
    )

  // Leaf (default version)
  def init[A](id: String)(value: => A)(using scope: TaskScope): Command[A] =
    init[A](id, TaskVersion.v1)(value)

  // 1 dep
  def init[A, D1](id: String, version: TaskVersion)(
      d1: Task[D1],
  )(body: D1 => A < (Async & Abort[Throwable]))(using scope: TaskScope): Command[A] =
    new Task.Command[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1),
      (_, args) => body(args(0).asInstanceOf[D1]),
    )

  // 1 dep (default version)
  def init[A, D1](id: String)(
      d1: Task[D1],
  )(body: D1 => A < (Async & Abort[Throwable]))(using scope: TaskScope): Command[A] =
    init[A, D1](id, TaskVersion.v1)(d1)(body)

  // 2 deps
  def init[A, D1, D2](id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (D1, D2) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Command[A] =
    new Task.Command[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1, d2),
      (_, args) => body(args(0).asInstanceOf[D1], args(1).asInstanceOf[D2]),
    )

  // 2 deps (default version)
  def init[A, D1, D2](id: String)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (D1, D2) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Command[A] =
    init[A, D1, D2](id, TaskVersion.v1)(d1, d2)(body)

  // ---------------------------------------------------------------------
  // CLI-aware smart constructor (Phase 13, Task 52).
  //
  // `Command.cli` registers a leaf command that consumes CLI tokens via the
  // implicit [[CommandArgs]] instance. The body receives the parsed `Args`
  // value rather than the raw [[CommandContext]]. The CLI tokens are taken
  // from `ctx.args.raw`, which the scheduler wires up from the surrounding
  // `Workflow.runCli` call.
  //
  // Higher-arity overloads (deps + args) are deferred until the example
  // builds need them.
  // ---------------------------------------------------------------------

  /** CLI-aware leaf command with an explicit version. */
  def cli[Args, A](id: String, version: TaskVersion)(
      body: Args => A,
  )(using scope: TaskScope, parser: CommandArgs[Args]): Command[A] =
    new Task.Command[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Nil,
      (ctx, _) =>
        parser.parse(ctx.args.raw.toSeq) match
          case Result.Success(args) => body(args)
          case Result.Failure(err)  =>
            // Surface as Throwable; the scheduler wraps Throwable into
            // WorkflowError.TaskFailed. `Workflow.runCli` re-extracts the
            // original `CliParseError` from the carrier exception below.
            throw new CommandArgsParseException(err)
          case other =>
            throw new RuntimeException(s"Unexpected CLI parse result: $other"),
    )

  /** CLI-aware leaf command, default version. */
  def cli[Args, A](id: String)(
      body: Args => A,
  )(using scope: TaskScope, parser: CommandArgs[Args]): Command[A] =
    cli[Args, A](id, TaskVersion.v1)(body)

  /** Internal carrier so a [[CliParseError]] raised inside a CLI body can
    * traverse the scheduler's Throwable channel and be re-projected as a
    * typed `CliParseError` by [[Workflow.runCli]].
    */
  private[workflow] final class CommandArgsParseException(val error: CliParseError)
      extends RuntimeException(s"CLI parse failed: $error")
end Command
