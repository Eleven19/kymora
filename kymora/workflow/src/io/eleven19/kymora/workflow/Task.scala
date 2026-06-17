package io.eleven19.kymora.workflow

import kyo.*

sealed trait Task[+A] derives CanEqual:
  def id: TaskId
  def version: TaskVersion

object Task:
  /** Default memoized variant — cached on (id, version, dep fingerprints).
    * Public via Task.init smart constructors.
    */
  final class Cached[A] private[workflow] (
      val id: TaskId,
      val version: TaskVersion,
      private[workflow] val deps: Seq[Task[?]],
      private[workflow] val body: (TaskContext, IndexedSeq[Any]) => A < (Async & Abort[Throwable]),
  ) extends Task[A]

  // Leaf (no deps)
  def init[A](id: String, version: TaskVersion = TaskVersion.v1)(
      value: => A,
  )(using scope: TaskScope): Task[A] =
    new Cached[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Nil,
      (_, _) => value,
    )

  // 1 dep
  def init[A, D1](id: String, version: TaskVersion)(
      d1: Task[D1],
  )(body: D1 => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    new Cached[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1),
      (_, args) => body(args(0).asInstanceOf[D1]),
    )

  // 1 dep (default version)
  def init[A, D1](id: String)(
      d1: Task[D1],
  )(body: D1 => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    init[A, D1](id, TaskVersion.v1)(d1)(body)

  // 2 deps
  def init[A, D1, D2](id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (D1, D2) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    new Cached[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1, d2),
      (_, args) => body(args(0).asInstanceOf[D1], args(1).asInstanceOf[D2]),
    )

  // 2 deps (default version)
  def init[A, D1, D2](id: String)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (D1, D2) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    init[A, D1, D2](id, TaskVersion.v1)(d1, d2)(body)
end Task
