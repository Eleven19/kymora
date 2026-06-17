package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.VPath
import kyo.*

sealed trait Task[+A] derives CanEqual:
  def id: TaskId
  def version: TaskVersion

object Task:
  /** File/directory input. Re-evaluated each run (path content rehashed via VFS).
    * Contributes to dependents' cache keys via the embedded fingerprint.
    *
    * Engine constants:
    *   version = TaskVersion.v1
    *   bodyHash = blake3("source:v1")
    */
  final class Source private[workflow] (
      val id: TaskId,
      private[workflow] val path: VPath,
      private[workflow] val quick: Boolean,
  ) extends Task[VPathRef]:
    val version: TaskVersion = TaskVersion.v1
  end Source

  object Source:
    def init(id: String)(path: VPath)(using scope: TaskScope): Source =
      new Source(TaskId.unsafe(scope.qualify(id).value), path, quick = false)
    def quick(id: String)(path: VPath)(using scope: TaskScope): Source =
      new Source(TaskId.unsafe(scope.qualify(id).value), path, quick = true)
  end Source

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

  // 3 deps
  def init[A, D1, D2, D3](id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
  )(body: (D1, D2, D3) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    new Cached[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1, d2, d3),
      (_, args) =>
        body(
          args(0).asInstanceOf[D1],
          args(1).asInstanceOf[D2],
          args(2).asInstanceOf[D3],
        ),
    )

  // 3 deps (default version)
  def init[A, D1, D2, D3](id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
  )(body: (D1, D2, D3) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    init[A, D1, D2, D3](id, TaskVersion.v1)(d1, d2, d3)(body)

  // 4 deps
  def init[A, D1, D2, D3, D4](id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
  )(body: (D1, D2, D3, D4) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    new Cached[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1, d2, d3, d4),
      (_, args) =>
        body(
          args(0).asInstanceOf[D1],
          args(1).asInstanceOf[D2],
          args(2).asInstanceOf[D3],
          args(3).asInstanceOf[D4],
        ),
    )

  // 4 deps (default version)
  def init[A, D1, D2, D3, D4](id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
  )(body: (D1, D2, D3, D4) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    init[A, D1, D2, D3, D4](id, TaskVersion.v1)(d1, d2, d3, d4)(body)

  // 5 deps
  def init[A, D1, D2, D3, D4, D5](id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
      d5: Task[D5],
  )(body: (D1, D2, D3, D4, D5) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    new Cached[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1, d2, d3, d4, d5),
      (_, args) =>
        body(
          args(0).asInstanceOf[D1],
          args(1).asInstanceOf[D2],
          args(2).asInstanceOf[D3],
          args(3).asInstanceOf[D4],
          args(4).asInstanceOf[D5],
        ),
    )

  // 5 deps (default version)
  def init[A, D1, D2, D3, D4, D5](id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
      d5: Task[D5],
  )(body: (D1, D2, D3, D4, D5) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    init[A, D1, D2, D3, D4, D5](id, TaskVersion.v1)(d1, d2, d3, d4, d5)(body)

  // 6 deps
  def init[A, D1, D2, D3, D4, D5, D6](id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
      d5: Task[D5],
      d6: Task[D6],
  )(body: (D1, D2, D3, D4, D5, D6) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
  ): Task[A] =
    new Cached[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1, d2, d3, d4, d5, d6),
      (_, args) =>
        body(
          args(0).asInstanceOf[D1],
          args(1).asInstanceOf[D2],
          args(2).asInstanceOf[D3],
          args(3).asInstanceOf[D4],
          args(4).asInstanceOf[D5],
          args(5).asInstanceOf[D6],
        ),
    )

  // 6 deps (default version)
  def init[A, D1, D2, D3, D4, D5, D6](id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
      d5: Task[D5],
      d6: Task[D6],
  )(body: (D1, D2, D3, D4, D5, D6) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
  ): Task[A] =
    init[A, D1, D2, D3, D4, D5, D6](id, TaskVersion.v1)(d1, d2, d3, d4, d5, d6)(body)

  /** Same caching contract as Cached, but the .dest/ directory is preserved
    * across rebuilds — bodies see prior outputs and may update in place
    * (incremental compilers, accumulating analyses, zinc-style stores).
    * Constructed via Task.persistent smart constructors.
    */
  final class Persistent[A] private[workflow] (
      val id: TaskId,
      val version: TaskVersion,
      private[workflow] val deps: Seq[Task[?]],
      private[workflow] val body: (TaskContext, IndexedSeq[Any]) => A < (Async & Abort[Throwable]),
  ) extends Task[A]

  // Leaf (no deps)
  def persistent[A](id: String, version: TaskVersion)(
      value: => A,
  )(using scope: TaskScope): Task[A] =
    new Persistent[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Nil,
      (_, _) => value,
    )

  // Leaf (default version)
  def persistent[A](id: String)(value: => A)(using scope: TaskScope): Task[A] =
    persistent[A](id, TaskVersion.v1)(value)

  // 1 dep
  def persistent[A, D1](id: String, version: TaskVersion)(
      d1: Task[D1],
  )(body: D1 => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    new Persistent[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1),
      (_, args) => body(args(0).asInstanceOf[D1]),
    )

  // 1 dep (default version)
  def persistent[A, D1](id: String)(
      d1: Task[D1],
  )(body: D1 => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    persistent[A, D1](id, TaskVersion.v1)(d1)(body)

  // 2 deps
  def persistent[A, D1, D2](id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (D1, D2) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    new Persistent[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1, d2),
      (_, args) => body(args(0).asInstanceOf[D1], args(1).asInstanceOf[D2]),
    )

  // 2 deps (default version)
  def persistent[A, D1, D2](id: String)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (D1, D2) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    persistent[A, D1, D2](id, TaskVersion.v1)(d1, d2)(body)

  // 3 deps
  def persistent[A, D1, D2, D3](id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
  )(body: (D1, D2, D3) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    new Persistent[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1, d2, d3),
      (_, args) =>
        body(
          args(0).asInstanceOf[D1],
          args(1).asInstanceOf[D2],
          args(2).asInstanceOf[D3],
        ),
    )

  // 3 deps (default version)
  def persistent[A, D1, D2, D3](id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
  )(body: (D1, D2, D3) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    persistent[A, D1, D2, D3](id, TaskVersion.v1)(d1, d2, d3)(body)

  // 4 deps
  def persistent[A, D1, D2, D3, D4](id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
  )(body: (D1, D2, D3, D4) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    new Persistent[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1, d2, d3, d4),
      (_, args) =>
        body(
          args(0).asInstanceOf[D1],
          args(1).asInstanceOf[D2],
          args(2).asInstanceOf[D3],
          args(3).asInstanceOf[D4],
        ),
    )

  // 4 deps (default version)
  def persistent[A, D1, D2, D3, D4](id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
  )(body: (D1, D2, D3, D4) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    persistent[A, D1, D2, D3, D4](id, TaskVersion.v1)(d1, d2, d3, d4)(body)

  // 5 deps
  def persistent[A, D1, D2, D3, D4, D5](id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
      d5: Task[D5],
  )(body: (D1, D2, D3, D4, D5) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    new Persistent[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1, d2, d3, d4, d5),
      (_, args) =>
        body(
          args(0).asInstanceOf[D1],
          args(1).asInstanceOf[D2],
          args(2).asInstanceOf[D3],
          args(3).asInstanceOf[D4],
          args(4).asInstanceOf[D5],
        ),
    )

  // 5 deps (default version)
  def persistent[A, D1, D2, D3, D4, D5](id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
      d5: Task[D5],
  )(body: (D1, D2, D3, D4, D5) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    persistent[A, D1, D2, D3, D4, D5](id, TaskVersion.v1)(d1, d2, d3, d4, d5)(body)

  // 6 deps
  def persistent[A, D1, D2, D3, D4, D5, D6](id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
      d5: Task[D5],
      d6: Task[D6],
  )(body: (D1, D2, D3, D4, D5, D6) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
  ): Task[A] =
    new Persistent[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1, d2, d3, d4, d5, d6),
      (_, args) =>
        body(
          args(0).asInstanceOf[D1],
          args(1).asInstanceOf[D2],
          args(2).asInstanceOf[D3],
          args(3).asInstanceOf[D4],
          args(4).asInstanceOf[D5],
          args(5).asInstanceOf[D6],
        ),
    )

  // 6 deps (default version)
  def persistent[A, D1, D2, D3, D4, D5, D6](id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
      d5: Task[D5],
      d6: Task[D6],
  )(body: (D1, D2, D3, D4, D5, D6) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
  ): Task[A] =
    persistent[A, D1, D2, D3, D4, D5, D6](id, TaskVersion.v1)(d1, d2, d3, d4, d5, d6)(body)
end Task
