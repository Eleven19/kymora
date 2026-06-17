package io.eleven19.kymora.workflow

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
end Command
