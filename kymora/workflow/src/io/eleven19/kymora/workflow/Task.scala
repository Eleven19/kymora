package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.VPath
import kyo.*
import scala.annotation.publicInBinary

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
    *
    * Built via [[Task.source]] / [[Task.sourceQuick]].
    */
  final class Source @publicInBinary private[workflow] (
      val id: TaskId,
      private[workflow] val path: VPath,
      private[workflow] val quick: Boolean,
  ) extends Task[VPathRef]:
    val version: TaskVersion = TaskVersion.v1
  end Source

  /** Pure-value input. Re-evaluated each run; contributes to dependents'
    * cache keys via the hash of the read value (not stored as a blob).
    *
    * Engine constants:
    *   version = TaskVersion.v1
    *   bodyHash = blake3("input:v1")
    *
    * Built via [[Task.input]].
    */
  final class Input[A] @publicInBinary private[workflow] (
      val id: TaskId,
      private[workflow] val read: () => A < (Async & Abort[Throwable]),
      private[workflow] val hashable: Hashable[A],
  ) extends Task[A]:
    val version: TaskVersion = TaskVersion.v1
  end Input

  /** Build a [[Source]] task under the ambient [[TaskScope]]. The path is
    * rehashed on every run via VFS (content hashing).
    */
  inline def source(inline id: String)(path: VPath)(using scope: TaskScope): Source =
    new Source(TaskId.unsafe(scope.qualify(id).value), path, quick = false)

  /** Build a [[Source]] task with the "quick" flag set — the engine hashes the
    * path string itself rather than reading its content.
    */
  inline def sourceQuick(inline id: String)(path: VPath)(using scope: TaskScope): Source =
    new Source(TaskId.unsafe(scope.qualify(id).value), path, quick = true)

  /** Build an [[Input]] task under the ambient [[TaskScope]]. The `read`
    * thunk is re-evaluated on every run; its value is hashed via the
    * implicit [[Hashable]] and contributes to dependents' cache keys.
    */
  inline def input[A](inline id: String)(read: => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
      h: Hashable[A],
  ): Input[A] =
    new Input[A](TaskId.unsafe(scope.qualify(id).value), () => read, h)

  /** Default memoized variant — cached on (id, version, dep fingerprints, optional param hash).
    *
    * `paramHash` is `Maybe.empty` for unparameterized cached tasks built via
    * `Task.cached[A](...)`. The parameterized variant
    * `Task.cached[A, P](...)` populates it via the supplied `Hashable[P]`, so
    * different `P` values produce different cache entries against the same
    * `TaskId`.
    *
    * Public via Task.cached smart constructors ([[Task.init]] is an alias).
    */
  final class Cached[A] @publicInBinary private[workflow] (
      val id: TaskId,
      val version: TaskVersion,
      private[workflow] val deps: Seq[Task[?]],
      private[workflow] val body: (TaskContext, IndexedSeq[Any]) => A < (Async & Abort[Throwable]),
      private[workflow] val paramHash: Maybe[Fingerprint] = Maybe.empty,
  ) extends Task[A]

  // ───────────────────────── cached (canonical name) ─────────────────────────

  // Leaf (no deps)
  inline def cached[A](inline id: String, version: TaskVersion)(
      value: => A,
  )(using scope: TaskScope): Task[A] =
    new Cached[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Nil,
      (_, _) => value,
    )

  // Leaf (default version)
  inline def cached[A](inline id: String)(value: => A)(using scope: TaskScope): Task[A] =
    cached[A](id, TaskVersion.v1)(value)

  // 1 dep
  inline def cached[A, D1](inline id: String, version: TaskVersion)(
      d1: Task[D1],
  )(body: D1 => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    new Cached[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1),
      (_, args) => body(args(0).asInstanceOf[D1]),
    )

  // 1 dep (default version)
  inline def cached[A, D1](inline id: String)(
      d1: Task[D1],
  )(body: D1 => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    cached[A, D1](id, TaskVersion.v1)(d1)(body)

  // 2 deps
  inline def cached[A, D1, D2](inline id: String, version: TaskVersion)(
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
  inline def cached[A, D1, D2](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (D1, D2) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    cached[A, D1, D2](id, TaskVersion.v1)(d1, d2)(body)

  // 3 deps
  inline def cached[A, D1, D2, D3](inline id: String, version: TaskVersion)(
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
  inline def cached[A, D1, D2, D3](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
  )(body: (D1, D2, D3) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    cached[A, D1, D2, D3](id, TaskVersion.v1)(d1, d2, d3)(body)

  // 4 deps
  inline def cached[A, D1, D2, D3, D4](inline id: String, version: TaskVersion)(
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
  inline def cached[A, D1, D2, D3, D4](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
  )(body: (D1, D2, D3, D4) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    cached[A, D1, D2, D3, D4](id, TaskVersion.v1)(d1, d2, d3, d4)(body)

  // 5 deps
  inline def cached[A, D1, D2, D3, D4, D5](inline id: String, version: TaskVersion)(
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
  inline def cached[A, D1, D2, D3, D4, D5](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
      d5: Task[D5],
  )(body: (D1, D2, D3, D4, D5) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    cached[A, D1, D2, D3, D4, D5](id, TaskVersion.v1)(d1, d2, d3, d4, d5)(body)

  // 6 deps
  inline def cached[A, D1, D2, D3, D4, D5, D6](inline id: String, version: TaskVersion)(
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
  inline def cached[A, D1, D2, D3, D4, D5, D6](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
      d5: Task[D5],
      d6: Task[D6],
  )(body: (D1, D2, D3, D4, D5, D6) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
  ): Task[A] =
    cached[A, D1, D2, D3, D4, D5, D6](id, TaskVersion.v1)(d1, d2, d3, d4, d5, d6)(body)

  // ─────────────────────── init (alias for cached) ────────────────────────
  //
  // Kept for Kyo-convention compatibility. Prefer [[cached]] for new code
  // (more consistent with task-kind naming: cached / persistent / command /
  // source / input).

  /** Alias for [[cached]] — kept for Kyo-convention compatibility.
    * Prefer [[cached]] for new code. */
  inline def init[A](inline id: String, version: TaskVersion)(
      value: => A,
  )(using scope: TaskScope): Task[A] =
    cached[A](id, version)(value)

  /** Alias for [[cached]] (default version). */
  inline def init[A](inline id: String)(value: => A)(using scope: TaskScope): Task[A] =
    cached[A](id)(value)

  /** Alias for [[cached]] (1 dep). */
  inline def init[A, D1](inline id: String, version: TaskVersion)(
      d1: Task[D1],
  )(body: D1 => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    cached[A, D1](id, version)(d1)(body)

  /** Alias for [[cached]] (1 dep, default version). */
  inline def init[A, D1](inline id: String)(
      d1: Task[D1],
  )(body: D1 => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    cached[A, D1](id)(d1)(body)

  /** Alias for [[cached]] (2 deps). */
  inline def init[A, D1, D2](inline id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (D1, D2) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    cached[A, D1, D2](id, version)(d1, d2)(body)

  /** Alias for [[cached]] (2 deps, default version). */
  inline def init[A, D1, D2](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (D1, D2) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    cached[A, D1, D2](id)(d1, d2)(body)

  /** Alias for [[cached]] (3 deps). */
  inline def init[A, D1, D2, D3](inline id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
  )(body: (D1, D2, D3) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    cached[A, D1, D2, D3](id, version)(d1, d2, d3)(body)

  /** Alias for [[cached]] (3 deps, default version). */
  inline def init[A, D1, D2, D3](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
  )(body: (D1, D2, D3) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    cached[A, D1, D2, D3](id)(d1, d2, d3)(body)

  /** Alias for [[cached]] (4 deps). */
  inline def init[A, D1, D2, D3, D4](inline id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
  )(body: (D1, D2, D3, D4) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    cached[A, D1, D2, D3, D4](id, version)(d1, d2, d3, d4)(body)

  /** Alias for [[cached]] (4 deps, default version). */
  inline def init[A, D1, D2, D3, D4](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
  )(body: (D1, D2, D3, D4) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    cached[A, D1, D2, D3, D4](id)(d1, d2, d3, d4)(body)

  /** Alias for [[cached]] (5 deps). */
  inline def init[A, D1, D2, D3, D4, D5](inline id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
      d5: Task[D5],
  )(body: (D1, D2, D3, D4, D5) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    cached[A, D1, D2, D3, D4, D5](id, version)(d1, d2, d3, d4, d5)(body)

  /** Alias for [[cached]] (5 deps, default version). */
  inline def init[A, D1, D2, D3, D4, D5](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
      d5: Task[D5],
  )(body: (D1, D2, D3, D4, D5) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    cached[A, D1, D2, D3, D4, D5](id)(d1, d2, d3, d4, d5)(body)

  /** Alias for [[cached]] (6 deps). */
  inline def init[A, D1, D2, D3, D4, D5, D6](inline id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
      d5: Task[D5],
      d6: Task[D6],
  )(body: (D1, D2, D3, D4, D5, D6) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
  ): Task[A] =
    cached[A, D1, D2, D3, D4, D5, D6](id, version)(d1, d2, d3, d4, d5, d6)(body)

  /** Alias for [[cached]] (6 deps, default version). */
  inline def init[A, D1, D2, D3, D4, D5, D6](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
      d5: Task[D5],
      d6: Task[D6],
  )(body: (D1, D2, D3, D4, D5, D6) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
  ): Task[A] =
    cached[A, D1, D2, D3, D4, D5, D6](id)(d1, d2, d3, d4, d5, d6)(body)

  // ──────────────────── parameterized cached (P joins cache key) ─────────────
  //
  // `Task.cached[A, P]` returns a `P => Task[A]`. The returned function builds
  // a new `Task.Cached[A]` whose `paramHash` is derived from the supplied
  // `Hashable[P]`, so different `P` values produce distinct cache entries
  // against the same `TaskId`. We cap dep arities at 3 for parameterized
  // variants to keep the surface manageable; users needing more can join deps
  // through an intermediate Cached task.

  /** Parameterized [[Cached]] (leaf). The returned `P => Task[A]` produces
    * a Cached task whose `paramHash` is folded into its `inputsHash`.
    */
  inline def cached[A, P](inline id: String, version: TaskVersion)(
      body: P => A < (Async & Abort[Throwable]),
  )(using scope: TaskScope, hp: Hashable[P]): P => Task[A] =
    val taskId = TaskId.unsafe(scope.qualify(id).value)
    (p: P) =>
      val ph = hp.hash(p)
      new Cached[A](
        taskId,
        version,
        Nil,
        (_, _) => body(p),
        Maybe(ph),
      )

  /** Parameterized [[Cached]] (leaf, default version). */
  inline def cached[A, P](inline id: String)(
      body: P => A < (Async & Abort[Throwable]),
  )(using scope: TaskScope, hp: Hashable[P]): P => Task[A] =
    cached[A, P](id, TaskVersion.v1)(body)

  /** Parameterized [[Cached]] with 1 dep. */
  inline def cached[A, P, D1](inline id: String, version: TaskVersion)(
      d1: Task[D1],
  )(body: (P, D1) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
      hp: Hashable[P],
  ): P => Task[A] =
    val taskId = TaskId.unsafe(scope.qualify(id).value)
    (p: P) =>
      val ph = hp.hash(p)
      new Cached[A](
        taskId,
        version,
        Seq(d1),
        (_, args) => body(p, args(0).asInstanceOf[D1]),
        Maybe(ph),
      )

  /** Parameterized [[Cached]] with 1 dep (default version). */
  inline def cached[A, P, D1](inline id: String)(
      d1: Task[D1],
  )(body: (P, D1) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
      hp: Hashable[P],
  ): P => Task[A] =
    cached[A, P, D1](id, TaskVersion.v1)(d1)(body)

  /** Parameterized [[Cached]] with 2 deps. */
  inline def cached[A, P, D1, D2](inline id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (P, D1, D2) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
      hp: Hashable[P],
  ): P => Task[A] =
    val taskId = TaskId.unsafe(scope.qualify(id).value)
    (p: P) =>
      val ph = hp.hash(p)
      new Cached[A](
        taskId,
        version,
        Seq(d1, d2),
        (_, args) => body(p, args(0).asInstanceOf[D1], args(1).asInstanceOf[D2]),
        Maybe(ph),
      )

  /** Parameterized [[Cached]] with 2 deps (default version). */
  inline def cached[A, P, D1, D2](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (P, D1, D2) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
      hp: Hashable[P],
  ): P => Task[A] =
    cached[A, P, D1, D2](id, TaskVersion.v1)(d1, d2)(body)

  /** Parameterized [[Cached]] with 3 deps. */
  inline def cached[A, P, D1, D2, D3](inline id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
  )(body: (P, D1, D2, D3) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
      hp: Hashable[P],
  ): P => Task[A] =
    val taskId = TaskId.unsafe(scope.qualify(id).value)
    (p: P) =>
      val ph = hp.hash(p)
      new Cached[A](
        taskId,
        version,
        Seq(d1, d2, d3),
        (_, args) =>
          body(
            p,
            args(0).asInstanceOf[D1],
            args(1).asInstanceOf[D2],
            args(2).asInstanceOf[D3],
          ),
        Maybe(ph),
      )

  /** Parameterized [[Cached]] with 3 deps (default version). */
  inline def cached[A, P, D1, D2, D3](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
  )(body: (P, D1, D2, D3) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
      hp: Hashable[P],
  ): P => Task[A] =
    cached[A, P, D1, D2, D3](id, TaskVersion.v1)(d1, d2, d3)(body)

  /** Same caching contract as Cached, but the .dest/ directory is preserved
    * across rebuilds — bodies see prior outputs and may update in place
    * (incremental compilers, accumulating analyses, zinc-style stores).
    * Constructed via Task.persistent smart constructors.
    */
  final class Persistent[A] @publicInBinary private[workflow] (
      val id: TaskId,
      val version: TaskVersion,
      private[workflow] val deps: Seq[Task[?]],
      private[workflow] val body: (TaskContext, IndexedSeq[Any]) => A < (Async & Abort[Throwable]),
      private[workflow] val paramHash: Maybe[Fingerprint] = Maybe.empty,
  ) extends Task[A]

  // Leaf (no deps)
  inline def persistent[A](inline id: String, version: TaskVersion)(
      value: => A,
  )(using scope: TaskScope): Task[A] =
    new Persistent[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Nil,
      (_, _) => value,
    )

  // Leaf (default version)
  inline def persistent[A](inline id: String)(value: => A)(using scope: TaskScope): Task[A] =
    persistent[A](id, TaskVersion.v1)(value)

  // 1 dep
  inline def persistent[A, D1](inline id: String, version: TaskVersion)(
      d1: Task[D1],
  )(body: D1 => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    new Persistent[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1),
      (_, args) => body(args(0).asInstanceOf[D1]),
    )

  // 1 dep (default version)
  inline def persistent[A, D1](inline id: String)(
      d1: Task[D1],
  )(body: D1 => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    persistent[A, D1](id, TaskVersion.v1)(d1)(body)

  // 2 deps
  inline def persistent[A, D1, D2](inline id: String, version: TaskVersion)(
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
  inline def persistent[A, D1, D2](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (D1, D2) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    persistent[A, D1, D2](id, TaskVersion.v1)(d1, d2)(body)

  // 3 deps
  inline def persistent[A, D1, D2, D3](inline id: String, version: TaskVersion)(
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
  inline def persistent[A, D1, D2, D3](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
  )(body: (D1, D2, D3) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    persistent[A, D1, D2, D3](id, TaskVersion.v1)(d1, d2, d3)(body)

  // 4 deps
  inline def persistent[A, D1, D2, D3, D4](inline id: String, version: TaskVersion)(
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
  inline def persistent[A, D1, D2, D3, D4](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
  )(body: (D1, D2, D3, D4) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    persistent[A, D1, D2, D3, D4](id, TaskVersion.v1)(d1, d2, d3, d4)(body)

  // 5 deps
  inline def persistent[A, D1, D2, D3, D4, D5](inline id: String, version: TaskVersion)(
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
  inline def persistent[A, D1, D2, D3, D4, D5](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
      d4: Task[D4],
      d5: Task[D5],
  )(body: (D1, D2, D3, D4, D5) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Task[A] =
    persistent[A, D1, D2, D3, D4, D5](id, TaskVersion.v1)(d1, d2, d3, d4, d5)(body)

  // 6 deps
  inline def persistent[A, D1, D2, D3, D4, D5, D6](inline id: String, version: TaskVersion)(
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
  inline def persistent[A, D1, D2, D3, D4, D5, D6](inline id: String)(
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

  // ─────────────── parameterized persistent (P joins cache key) ──────────────

  /** Parameterized [[Persistent]] (leaf). */
  inline def persistent[A, P](inline id: String, version: TaskVersion)(
      body: P => A < (Async & Abort[Throwable]),
  )(using scope: TaskScope, hp: Hashable[P]): P => Task[A] =
    val taskId = TaskId.unsafe(scope.qualify(id).value)
    (p: P) =>
      val ph = hp.hash(p)
      new Persistent[A](
        taskId,
        version,
        Nil,
        (_, _) => body(p),
        Maybe(ph),
      )

  /** Parameterized [[Persistent]] (leaf, default version). */
  inline def persistent[A, P](inline id: String)(
      body: P => A < (Async & Abort[Throwable]),
  )(using scope: TaskScope, hp: Hashable[P]): P => Task[A] =
    persistent[A, P](id, TaskVersion.v1)(body)

  /** Parameterized [[Persistent]] with 1 dep. */
  inline def persistent[A, P, D1](inline id: String, version: TaskVersion)(
      d1: Task[D1],
  )(body: (P, D1) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
      hp: Hashable[P],
  ): P => Task[A] =
    val taskId = TaskId.unsafe(scope.qualify(id).value)
    (p: P) =>
      val ph = hp.hash(p)
      new Persistent[A](
        taskId,
        version,
        Seq(d1),
        (_, args) => body(p, args(0).asInstanceOf[D1]),
        Maybe(ph),
      )

  /** Parameterized [[Persistent]] with 1 dep (default version). */
  inline def persistent[A, P, D1](inline id: String)(
      d1: Task[D1],
  )(body: (P, D1) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
      hp: Hashable[P],
  ): P => Task[A] =
    persistent[A, P, D1](id, TaskVersion.v1)(d1)(body)

  /** Parameterized [[Persistent]] with 2 deps. */
  inline def persistent[A, P, D1, D2](inline id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (P, D1, D2) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
      hp: Hashable[P],
  ): P => Task[A] =
    val taskId = TaskId.unsafe(scope.qualify(id).value)
    (p: P) =>
      val ph = hp.hash(p)
      new Persistent[A](
        taskId,
        version,
        Seq(d1, d2),
        (_, args) => body(p, args(0).asInstanceOf[D1], args(1).asInstanceOf[D2]),
        Maybe(ph),
      )

  /** Parameterized [[Persistent]] with 2 deps (default version). */
  inline def persistent[A, P, D1, D2](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (P, D1, D2) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
      hp: Hashable[P],
  ): P => Task[A] =
    persistent[A, P, D1, D2](id, TaskVersion.v1)(d1, d2)(body)

  /** Parameterized [[Persistent]] with 3 deps. */
  inline def persistent[A, P, D1, D2, D3](inline id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
  )(body: (P, D1, D2, D3) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
      hp: Hashable[P],
  ): P => Task[A] =
    val taskId = TaskId.unsafe(scope.qualify(id).value)
    (p: P) =>
      val ph = hp.hash(p)
      new Persistent[A](
        taskId,
        version,
        Seq(d1, d2, d3),
        (_, args) =>
          body(
            p,
            args(0).asInstanceOf[D1],
            args(1).asInstanceOf[D2],
            args(2).asInstanceOf[D3],
          ),
        Maybe(ph),
      )

  /** Parameterized [[Persistent]] with 3 deps (default version). */
  inline def persistent[A, P, D1, D2, D3](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
      d3: Task[D3],
  )(body: (P, D1, D2, D3) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
      hp: Hashable[P],
  ): P => Task[A] =
    persistent[A, P, D1, D2, D3](id, TaskVersion.v1)(d1, d2, d3)(body)

  /** Command kind: always runs (no memoization of own output). Deps still
    * memoize normally.
    *
    * The body receives a [[TaskContext]] just like Cached/Persistent. CLI
    * argument parsing is handled by [[io.eleven19.kymora.workflow.cli.Cli]],
    * which delegates to the parameterized `command[A, P]` variant.
    *
    * `paramHash` does NOT affect command execution (commands never cache
    * their own output), but is carried symmetrically so the field signature
    * stays consistent across kinds.
    */
  final class Command[A] @publicInBinary private[workflow] (
      val id: TaskId,
      val version: TaskVersion,
      private[workflow] val deps: Seq[Task[?]],
      private[workflow] val body: (TaskContext, IndexedSeq[Any]) => A < (Async & Abort[Throwable]),
      private[workflow] val paramHash: Maybe[Fingerprint] = Maybe.empty,
  ) extends Task[A]

  /** Build a [[Command]] task under the ambient [[TaskScope]]. Commands
    * always run (their own output is never memoized), but their
    * dependencies still memoize normally.
    */
  // Leaf (no deps)
  inline def command[A](inline id: String, version: TaskVersion)(
      value: => A,
  )(using scope: TaskScope): Command[A] =
    new Command[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Nil,
      (_, _) => value,
    )

  // Leaf (default version)
  inline def command[A](inline id: String)(value: => A)(using scope: TaskScope): Command[A] =
    command[A](id, TaskVersion.v1)(value)

  // 1 dep
  inline def command[A, D1](inline id: String, version: TaskVersion)(
      d1: Task[D1],
  )(body: D1 => A < (Async & Abort[Throwable]))(using scope: TaskScope): Command[A] =
    new Command[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1),
      (_, args) => body(args(0).asInstanceOf[D1]),
    )

  // 1 dep (default version)
  inline def command[A, D1](inline id: String)(
      d1: Task[D1],
  )(body: D1 => A < (Async & Abort[Throwable]))(using scope: TaskScope): Command[A] =
    command[A, D1](id, TaskVersion.v1)(d1)(body)

  // 2 deps
  inline def command[A, D1, D2](inline id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (D1, D2) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Command[A] =
    new Command[A](
      TaskId.unsafe(scope.qualify(id).value),
      version,
      Seq(d1, d2),
      (_, args) => body(args(0).asInstanceOf[D1], args(1).asInstanceOf[D2]),
    )

  // 2 deps (default version)
  inline def command[A, D1, D2](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (D1, D2) => A < (Async & Abort[Throwable]))(using scope: TaskScope): Command[A] =
    command[A, D1, D2](id, TaskVersion.v1)(d1, d2)(body)

  // ─────────────── parameterized command (P does NOT need Hashable) ──────────
  //
  // Commands never consult or populate the cache, so the parameterized
  // command variants don't require a `Hashable[P]` — `paramHash` remains
  // `Maybe.empty` even with parameters. The factory returns `P => Command[A]`
  // so `Cli.runWith` (and downstream callers) can apply parsed arguments.

  /** Parameterized [[Command]] (leaf). The returned `P => Command[A]` builds a
    * Command whose body closes over the supplied `P`.
    */
  inline def command[A, P](inline id: String, version: TaskVersion)(
      body: P => A < (Async & Abort[Throwable]),
  )(using scope: TaskScope): P => Command[A] =
    val taskId = TaskId.unsafe(scope.qualify(id).value)
    (p: P) =>
      new Command[A](
        taskId,
        version,
        Nil,
        (_, _) => body(p),
      )

  /** Parameterized [[Command]] (leaf, default version). */
  inline def command[A, P](inline id: String)(
      body: P => A < (Async & Abort[Throwable]),
  )(using scope: TaskScope): P => Command[A] =
    command[A, P](id, TaskVersion.v1)(body)

  /** Parameterized [[Command]] with 1 dep. */
  inline def command[A, P, D1](inline id: String, version: TaskVersion)(
      d1: Task[D1],
  )(body: (P, D1) => A < (Async & Abort[Throwable]))(using scope: TaskScope): P => Command[A] =
    val taskId = TaskId.unsafe(scope.qualify(id).value)
    (p: P) =>
      new Command[A](
        taskId,
        version,
        Seq(d1),
        (_, args) => body(p, args(0).asInstanceOf[D1]),
      )

  /** Parameterized [[Command]] with 1 dep (default version). */
  inline def command[A, P, D1](inline id: String)(
      d1: Task[D1],
  )(body: (P, D1) => A < (Async & Abort[Throwable]))(using scope: TaskScope): P => Command[A] =
    command[A, P, D1](id, TaskVersion.v1)(d1)(body)

  /** Parameterized [[Command]] with 2 deps. */
  inline def command[A, P, D1, D2](inline id: String, version: TaskVersion)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (P, D1, D2) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
  ): P => Command[A] =
    val taskId = TaskId.unsafe(scope.qualify(id).value)
    (p: P) =>
      new Command[A](
        taskId,
        version,
        Seq(d1, d2),
        (_, args) => body(p, args(0).asInstanceOf[D1], args(1).asInstanceOf[D2]),
      )

  /** Parameterized [[Command]] with 2 deps (default version). */
  inline def command[A, P, D1, D2](inline id: String)(
      d1: Task[D1],
      d2: Task[D2],
  )(body: (P, D1, D2) => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
  ): P => Command[A] =
    command[A, P, D1, D2](id, TaskVersion.v1)(d1, d2)(body)
end Task
