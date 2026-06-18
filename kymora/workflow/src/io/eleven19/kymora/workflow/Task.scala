package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.ReadonlyVfs
import io.eleven19.kymora.vfs.VPath
import io.eleven19.kymora.vfs.Vfs
import kyo.*
import scala.annotation.publicInBinary

/** A typed node in a workflow dependency graph.
  *
  * Tasks are values: define them with the smart constructors on [[Task]], then execute them under [[Workflow]]. The
  * result type `A` is preserved through dependency wiring, so a `Task[Int]` can be passed to another task that expects
  * an `Int`.
  *
  * `Task.cached`, `Task.init`, and `Task.persistent` require `Cacheable[A]` and `Hashable[A]`. For ordinary case
  * classes, `derives Schema` is enough because workflow provides Schema-backed default instances; define a custom
  * [[Hashable]] when downstream invalidation needs custom identity.
  *
  * {{{
  * final case class Report(path: String) derives Schema
  *
  * val source = Task.source("source")(VPath.root / "input.txt")
  * val compile = Task.cached("compile")(source) { ref =>
  *     Report(ref.path.show)
  * }
  * val publish = Task.command("publish")(compile) { report =>
  *     Console.printLine(report.path).map(_ => report)
  * }
  *
  * Workflow.handle(runtime)(publish())
  * }}}
  */
sealed trait Task[+A] derives CanEqual:
    type ResultType <: A

    def id: TaskId
    def version: TaskVersion

    def apply()(using Frame): A < (Async & Workflow & Abort[WorkflowError]) =
        Workflow.run(this)

object Task:
    /** Effects available while task values are evaluated.
      *
      * Task values may run asynchronous Kyo code, access the ambient [[Workflow]], read/write through VFS path syntax,
      * and fail with either ordinary `Throwable`s or domain-level [[WorkflowError]] values.
      */
    type BodyEffects = Async & Workflow & Vfs & ReadonlyVfs & Abort[Throwable | WorkflowError]

    /** File/directory input. Re-evaluated each run (path content rehashed via VFS). Contributes to dependents' cache
      * keys via the embedded fingerprint.
      *
      * Engine constants: version = TaskVersion.v1 bodyHash = blake3("source:v1")
      *
      * Built via [[Task.source]] / [[Task.sourceQuick]].
      */
    final class Source @publicInBinary private[workflow] (
        val id: TaskId,
        private[workflow] val path: VPath,
        private[workflow] val quick: Boolean
    ) extends Task[VPathRef]:
        type ResultType = VPathRef
        val version: TaskVersion = TaskVersion.v1
    end Source

    /** Ordered multi-path input (Mill `Sources` analogue). Each path is read + hashed via the workflow runtime VFS; the
      * resulting `Chunk[VPathRef]` preserves input order. The dep-side aggregate fingerprint is order-sensitive
      * (reordering or duplicating paths invalidates downstream caches).
      *
      * Engine constants: version = TaskVersion.v1 bodyHash = blake3("sources:v1")
      *
      * Built via [[Task.sources]] / [[Task.sourcesQuick]].
      */
    final class Sources @publicInBinary private[workflow] (
        val id: TaskId,
        private[workflow] val paths: Chunk[VPath],
        private[workflow] val quick: Boolean
    ) extends Task[Chunk[VPathRef]]:
        type ResultType = Chunk[VPathRef]
        val version: TaskVersion = TaskVersion.v1
    end Sources

    /** Pure-value input. Re-evaluated each run; contributes to dependents' cache keys via the hash of the read value
      * (not stored as a blob).
      *
      * Engine constants: version = TaskVersion.v1 bodyHash = blake3("input:v1")
      *
      * Built via [[Task.input]].
      */
    final class Input[A] @publicInBinary private[workflow] (
        val id: TaskId,
        private[workflow] val read: () => A < BodyEffects,
        private[workflow] val hashable: Hashable[A]
    ) extends Task[A]:
        type ResultType = A
        val version: TaskVersion = TaskVersion.v1
    end Input

    /** Build a [[Source]] task under the ambient [[TaskScope]]. The path is rehashed on every run via VFS (content
      * hashing).
      */
    inline def source(inline id: String)(path: VPath)(using scope: TaskScope): Source =
        new Source(TaskId.unsafe(scope.qualify(id).value), path, quick = false)

    /** Build a [[Source]] task with the "quick" flag set — the engine hashes the path string itself rather than reading
      * its content.
      */
    inline def sourceQuick(inline id: String)(path: VPath)(using scope: TaskScope): Source =
        new Source(TaskId.unsafe(scope.qualify(id).value), path, quick = true)

    /** Build a [[Sources]] task under the ambient [[TaskScope]]. Each path is read + content-hashed on every run via
      * the workflow runtime VFS.
      */
    inline def sources(inline id: String)(paths: VPath*)(using scope: TaskScope): Sources =
        new Sources(TaskId.unsafe(scope.qualify(id).value), Chunk.from(paths), quick = false)

    /** Build a [[Sources]] task with the "quick" flag set — each path uses the cheaper size+mtime token rather than
      * content bytes.
      */
    inline def sourcesQuick(inline id: String)(paths: VPath*)(using scope: TaskScope): Sources =
        new Sources(TaskId.unsafe(scope.qualify(id).value), Chunk.from(paths), quick = true)

    /** Build an [[Input]] task under the ambient [[TaskScope]]. The `read` thunk is re-evaluated on every run; its
      * value is hashed via the implicit [[Hashable]] and contributes to dependents' cache keys.
      */
    inline def input[A](inline id: String)(read: => A < BodyEffects)(using
        scope: TaskScope,
        h: Hashable[A]
    ): Input[A] =
        new Input[A](TaskId.unsafe(scope.qualify(id).value), () => read, h)

    /** Default memoized variant — cached on (id, version, dep fingerprints, optional param hash).
      *
      * `paramHash` is `Maybe.empty` for unparameterized cached tasks built via `Task.cached[A](...)`. The parameterized
      * variant `Task.cached[A, P](...)` populates it via the supplied `Hashable[P]`, so different `P` values produce
      * different cache entries against the same `TaskId`.
      *
      * Public via Task.cached smart constructors ([[Task.init]] is an alias).
      */
    final class Cached[A] @publicInBinary private[workflow] (
        val id: TaskId,
        val version: TaskVersion,
        depTasks: Seq[Task[?]],
        private[workflow] val value: (TaskContext, IndexedSeq[Any]) => A < BodyEffects,
        private[workflow] val cacheable: Cacheable[A],
        private[workflow] val hashable: Hashable[A],
        private[workflow] val paramHash: Maybe[Fingerprint] = Maybe.empty
    ) extends Task[A]:
        type ResultType = A
        private[workflow] val deps: Seq[AnyTask] = depTasks.map(AnyTask(_))

    // ───────────────────────── cached (canonical name) ─────────────────────────

    // Leaf (no deps)
    inline def cached[A](inline id: String, version: TaskVersion)(
        value: => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        new Cached[A](
            TaskId.unsafe(scope.qualify(id).value),
            version,
            Nil,
            (_, _) => value,
            cacheable,
            hashable
        )

    // Leaf (default version)
    inline def cached[A](inline id: String)(value: => A < BodyEffects)(using
        scope: TaskScope,
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): Task[A] =
        cached[A](id, TaskVersion.v1)(value)

    // 1 dep
    inline def cached[A, D1](inline id: String, version: TaskVersion)(
        d1: Task[D1]
    )(value: D1 => A < BodyEffects)(using
        scope: TaskScope,
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): Task[A] =
        new Cached[A](
            TaskId.unsafe(scope.qualify(id).value),
            version,
            Seq(d1),
            (_, args) => value(args(0).asInstanceOf[D1]),
            cacheable,
            hashable
        )

    // 1 dep (default version)
    inline def cached[A, D1](inline id: String)(
        d1: Task[D1]
    )(value: D1 => A < BodyEffects)(using
        scope: TaskScope,
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): Task[A] =
        cached[A, D1](id, TaskVersion.v1)(d1)(value)

    // 2 deps
    inline def cached[A, D1, D2](inline id: String, version: TaskVersion)(
        d1: Task[D1],
        d2: Task[D2]
    )(value: (D1, D2) => A < BodyEffects)(using
        scope: TaskScope,
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): Task[A] =
        new Cached[A](
            TaskId.unsafe(scope.qualify(id).value),
            version,
            Seq(d1, d2),
            (_, args) => value(args(0).asInstanceOf[D1], args(1).asInstanceOf[D2]),
            cacheable,
            hashable
        )

    // 2 deps (default version)
    inline def cached[A, D1, D2](inline id: String)(
        d1: Task[D1],
        d2: Task[D2]
    )(value: (D1, D2) => A < BodyEffects)(using
        scope: TaskScope,
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): Task[A] =
        cached[A, D1, D2](id, TaskVersion.v1)(d1, d2)(value)

    // 3 deps
    inline def cached[A, D1, D2, D3](inline id: String, version: TaskVersion)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3]
    )(value: (D1, D2, D3) => A < BodyEffects)(using
        scope: TaskScope,
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): Task[A] =
        new Cached[A](
            TaskId.unsafe(scope.qualify(id).value),
            version,
            Seq(d1, d2, d3),
            (_, args) =>
                value(
                    args(0).asInstanceOf[D1],
                    args(1).asInstanceOf[D2],
                    args(2).asInstanceOf[D3]
                ),
            cacheable,
            hashable
        )

    // 3 deps (default version)
    inline def cached[A, D1, D2, D3](inline id: String)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3]
    )(value: (D1, D2, D3) => A < BodyEffects)(using
        scope: TaskScope,
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): Task[A] =
        cached[A, D1, D2, D3](id, TaskVersion.v1)(d1, d2, d3)(value)

    // 4 deps
    inline def cached[A, D1, D2, D3, D4](inline id: String, version: TaskVersion)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3],
        d4: Task[D4]
    )(value: (D1, D2, D3, D4) => A < BodyEffects)(using
        scope: TaskScope,
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): Task[A] =
        new Cached[A](
            TaskId.unsafe(scope.qualify(id).value),
            version,
            Seq(d1, d2, d3, d4),
            (_, args) =>
                value(
                    args(0).asInstanceOf[D1],
                    args(1).asInstanceOf[D2],
                    args(2).asInstanceOf[D3],
                    args(3).asInstanceOf[D4]
                ),
            cacheable,
            hashable
        )

    // 4 deps (default version)
    inline def cached[A, D1, D2, D3, D4](inline id: String)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3],
        d4: Task[D4]
    )(value: (D1, D2, D3, D4) => A < BodyEffects)(using
        scope: TaskScope,
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): Task[A] =
        cached[A, D1, D2, D3, D4](id, TaskVersion.v1)(d1, d2, d3, d4)(value)

    // 5 deps
    inline def cached[A, D1, D2, D3, D4, D5](inline id: String, version: TaskVersion)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3],
        d4: Task[D4],
        d5: Task[D5]
    )(value: (D1, D2, D3, D4, D5) => A < BodyEffects)(using
        scope: TaskScope,
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): Task[A] =
        new Cached[A](
            TaskId.unsafe(scope.qualify(id).value),
            version,
            Seq(d1, d2, d3, d4, d5),
            (_, args) =>
                value(
                    args(0).asInstanceOf[D1],
                    args(1).asInstanceOf[D2],
                    args(2).asInstanceOf[D3],
                    args(3).asInstanceOf[D4],
                    args(4).asInstanceOf[D5]
                ),
            cacheable,
            hashable
        )

    // 5 deps (default version)
    inline def cached[A, D1, D2, D3, D4, D5](inline id: String)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3],
        d4: Task[D4],
        d5: Task[D5]
    )(value: (D1, D2, D3, D4, D5) => A < BodyEffects)(using
        scope: TaskScope,
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): Task[A] =
        cached[A, D1, D2, D3, D4, D5](id, TaskVersion.v1)(d1, d2, d3, d4, d5)(value)

    // 6 deps
    inline def cached[A, D1, D2, D3, D4, D5, D6](inline id: String, version: TaskVersion)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3],
        d4: Task[D4],
        d5: Task[D5],
        d6: Task[D6]
    )(value: (D1, D2, D3, D4, D5, D6) => A < BodyEffects)(using
        scope: TaskScope,
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): Task[A] =
        new Cached[A](
            TaskId.unsafe(scope.qualify(id).value),
            version,
            Seq(d1, d2, d3, d4, d5, d6),
            (_, args) =>
                value(
                    args(0).asInstanceOf[D1],
                    args(1).asInstanceOf[D2],
                    args(2).asInstanceOf[D3],
                    args(3).asInstanceOf[D4],
                    args(4).asInstanceOf[D5],
                    args(5).asInstanceOf[D6]
                ),
            cacheable,
            hashable
        )

    // 6 deps (default version)
    inline def cached[A, D1, D2, D3, D4, D5, D6](inline id: String)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3],
        d4: Task[D4],
        d5: Task[D5],
        d6: Task[D6]
    )(value: (D1, D2, D3, D4, D5, D6) => A < BodyEffects)(using
        scope: TaskScope,
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): Task[A] =
        cached[A, D1, D2, D3, D4, D5, D6](id, TaskVersion.v1)(d1, d2, d3, d4, d5, d6)(value)

    // ─────────────────────── init (alias for cached) ────────────────────────
    //
    // Kept for Kyo-convention compatibility. Prefer [[cached]] for new code
    // (more consistent with task-kind naming: cached / persistent / command /
    // source / input).

    /** Alias for [[cached]] — kept for Kyo-convention compatibility. Prefer [[cached]] for new code.
      */
    inline def init[A](inline id: String, version: TaskVersion)(
        value: => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        cached[A](id, version)(value)

    /** Alias for [[cached]] (default version). */
    inline def init[A](inline id: String)(
        value: => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        cached[A](id)(value)

    /** Alias for [[cached]] (1 dep). */
    inline def init[A, D1](inline id: String, version: TaskVersion)(
        d1: Task[D1]
    )(body: D1 => A < BodyEffects)(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        cached[A, D1](id, version)(d1)(body)

    /** Alias for [[cached]] (1 dep, default version). */
    inline def init[A, D1](inline id: String)(
        d1: Task[D1]
    )(body: D1 => A < BodyEffects)(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        cached[A, D1](id)(d1)(body)

    /** Alias for [[cached]] (2 deps). */
    inline def init[A, D1, D2](inline id: String, version: TaskVersion)(
        d1: Task[D1],
        d2: Task[D2]
    )(
        body: (D1, D2) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        cached[A, D1, D2](id, version)(d1, d2)(body)

    /** Alias for [[cached]] (2 deps, default version). */
    inline def init[A, D1, D2](inline id: String)(
        d1: Task[D1],
        d2: Task[D2]
    )(
        body: (D1, D2) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        cached[A, D1, D2](id)(d1, d2)(body)

    /** Alias for [[cached]] (3 deps). */
    inline def init[A, D1, D2, D3](inline id: String, version: TaskVersion)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3]
    )(
        body: (D1, D2, D3) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        cached[A, D1, D2, D3](id, version)(d1, d2, d3)(body)

    /** Alias for [[cached]] (3 deps, default version). */
    inline def init[A, D1, D2, D3](inline id: String)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3]
    )(
        body: (D1, D2, D3) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        cached[A, D1, D2, D3](id)(d1, d2, d3)(body)

    /** Alias for [[cached]] (4 deps). */
    inline def init[A, D1, D2, D3, D4](inline id: String, version: TaskVersion)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3],
        d4: Task[D4]
    )(
        body: (D1, D2, D3, D4) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        cached[A, D1, D2, D3, D4](id, version)(d1, d2, d3, d4)(body)

    /** Alias for [[cached]] (4 deps, default version). */
    inline def init[A, D1, D2, D3, D4](inline id: String)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3],
        d4: Task[D4]
    )(
        body: (D1, D2, D3, D4) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        cached[A, D1, D2, D3, D4](id)(d1, d2, d3, d4)(body)

    /** Alias for [[cached]] (5 deps). */
    inline def init[A, D1, D2, D3, D4, D5](inline id: String, version: TaskVersion)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3],
        d4: Task[D4],
        d5: Task[D5]
    )(
        body: (D1, D2, D3, D4, D5) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        cached[A, D1, D2, D3, D4, D5](id, version)(d1, d2, d3, d4, d5)(body)

    /** Alias for [[cached]] (5 deps, default version). */
    inline def init[A, D1, D2, D3, D4, D5](inline id: String)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3],
        d4: Task[D4],
        d5: Task[D5]
    )(
        body: (D1, D2, D3, D4, D5) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        cached[A, D1, D2, D3, D4, D5](id)(d1, d2, d3, d4, d5)(body)

    /** Alias for [[cached]] (6 deps). */
    inline def init[A, D1, D2, D3, D4, D5, D6](inline id: String, version: TaskVersion)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3],
        d4: Task[D4],
        d5: Task[D5],
        d6: Task[D6]
    )(body: (D1, D2, D3, D4, D5, D6) => A < BodyEffects)(using
        scope: TaskScope,
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): Task[A] =
        cached[A, D1, D2, D3, D4, D5, D6](id, version)(d1, d2, d3, d4, d5, d6)(body)

    /** Alias for [[cached]] (6 deps, default version). */
    inline def init[A, D1, D2, D3, D4, D5, D6](inline id: String)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3],
        d4: Task[D4],
        d5: Task[D5],
        d6: Task[D6]
    )(body: (D1, D2, D3, D4, D5, D6) => A < BodyEffects)(using
        scope: TaskScope,
        cacheable: Cacheable[A],
        hashable: Hashable[A]
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

    /** Parameterized [[Cached]] (leaf). The returned `P => Task[A]` produces a Cached task whose `paramHash` is folded
      * into its `inputsHash`.
      */
    inline def cached[A, P](inline id: String, version: TaskVersion)(
        value: P => A < BodyEffects
    )(using
        scope: TaskScope,
        hp: Hashable[P],
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): P => Task[A] =
        val taskId = TaskId.unsafe(scope.qualify(id).value)
        (p: P) =>
            val ph = hp.hash(p)
            new Cached[A](
                taskId,
                version,
                Nil,
                (_, _) => value(p),
                cacheable,
                hashable,
                Maybe(ph)
            )

    /** Parameterized [[Cached]] (leaf, default version). */
    inline def cached[A, P](inline id: String)(
        value: P => A < BodyEffects
    )(using
        scope: TaskScope,
        hp: Hashable[P],
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): P => Task[A] =
        cached[A, P](id, TaskVersion.v1)(value)

    /** Parameterized [[Cached]] with 1 dep. */
    inline def cached[A, P, D1](inline id: String, version: TaskVersion)(
        d1: Task[D1]
    )(value: (P, D1) => A < BodyEffects)(using
        scope: TaskScope,
        hp: Hashable[P],
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): P => Task[A] =
        val taskId = TaskId.unsafe(scope.qualify(id).value)
        (p: P) =>
            val ph = hp.hash(p)
            new Cached[A](
                taskId,
                version,
                Seq(d1),
                (_, args) => value(p, args(0).asInstanceOf[D1]),
                cacheable,
                hashable,
                Maybe(ph)
            )

    /** Parameterized [[Cached]] with 1 dep (default version). */
    inline def cached[A, P, D1](inline id: String)(
        d1: Task[D1]
    )(value: (P, D1) => A < BodyEffects)(using
        scope: TaskScope,
        hp: Hashable[P],
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): P => Task[A] =
        cached[A, P, D1](id, TaskVersion.v1)(d1)(value)

    /** Parameterized [[Cached]] with 2 deps. */
    inline def cached[A, P, D1, D2](inline id: String, version: TaskVersion)(
        d1: Task[D1],
        d2: Task[D2]
    )(value: (P, D1, D2) => A < BodyEffects)(using
        scope: TaskScope,
        hp: Hashable[P],
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): P => Task[A] =
        val taskId = TaskId.unsafe(scope.qualify(id).value)
        (p: P) =>
            val ph = hp.hash(p)
            new Cached[A](
                taskId,
                version,
                Seq(d1, d2),
                (_, args) => value(p, args(0).asInstanceOf[D1], args(1).asInstanceOf[D2]),
                cacheable,
                hashable,
                Maybe(ph)
            )

    /** Parameterized [[Cached]] with 2 deps (default version). */
    inline def cached[A, P, D1, D2](inline id: String)(
        d1: Task[D1],
        d2: Task[D2]
    )(value: (P, D1, D2) => A < BodyEffects)(using
        scope: TaskScope,
        hp: Hashable[P],
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): P => Task[A] =
        cached[A, P, D1, D2](id, TaskVersion.v1)(d1, d2)(value)

    /** Parameterized [[Cached]] with 3 deps. */
    inline def cached[A, P, D1, D2, D3](inline id: String, version: TaskVersion)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3]
    )(value: (P, D1, D2, D3) => A < BodyEffects)(using
        scope: TaskScope,
        hp: Hashable[P],
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): P => Task[A] =
        val taskId = TaskId.unsafe(scope.qualify(id).value)
        (p: P) =>
            val ph = hp.hash(p)
            new Cached[A](
                taskId,
                version,
                Seq(d1, d2, d3),
                (_, args) =>
                    value(
                        p,
                        args(0).asInstanceOf[D1],
                        args(1).asInstanceOf[D2],
                        args(2).asInstanceOf[D3]
                    ),
                cacheable,
                hashable,
                Maybe(ph)
            )

    /** Parameterized [[Cached]] with 3 deps (default version). */
    inline def cached[A, P, D1, D2, D3](inline id: String)(
        d1: Task[D1],
        d2: Task[D2],
        d3: Task[D3]
    )(value: (P, D1, D2, D3) => A < BodyEffects)(using
        scope: TaskScope,
        hp: Hashable[P],
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): P => Task[A] =
        cached[A, P, D1, D2, D3](id, TaskVersion.v1)(d1, d2, d3)(value)

    /** Same caching contract as Cached, but the .dest/ directory is preserved across rebuilds. On cache hits the stored
      * value is decoded; on misses the value expression runs with access to the previous .dest contents.
      */
    final class Persistent[A] @publicInBinary private[workflow] (
        val id: TaskId,
        val version: TaskVersion,
        depTasks: Seq[Task[?]],
        private[workflow] val value: (TaskContext, IndexedSeq[Any]) => A < BodyEffects,
        private[workflow] val cacheable: Cacheable[A],
        private[workflow] val hashable: Hashable[A],
        private[workflow] val paramHash: Maybe[Fingerprint] = Maybe.empty
    ) extends Task[A]:
        type ResultType = A
        private[workflow] val deps: Seq[AnyTask] = depTasks.map(AnyTask(_))

    private def persistentTask[A](id: TaskId, version: TaskVersion, deps: Seq[Task[?]], paramHash: Maybe[Fingerprint])(
        value: (TaskContext, IndexedSeq[Any]) => A < BodyEffects
    )(using cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        new Persistent[A](id, version, deps, value, cacheable, hashable, paramHash)

    inline def persistent[A](inline id: String, version: TaskVersion)(
        value: => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        persistentTask[A](TaskId.unsafe(scope.qualify(id).value), version, Nil, Maybe.empty)((_, _) => value)

    inline def persistent[A](inline id: String)(value: => A < BodyEffects)(using
        scope: TaskScope,
        cacheable: Cacheable[A],
        hashable: Hashable[A]
    ): Task[A] =
        persistent[A](id, TaskVersion.v1)(value)

    inline def persistent[A, D1](inline id: String, version: TaskVersion)(d1: Task[D1])(
        value: D1 => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        persistentTask[A](TaskId.unsafe(scope.qualify(id).value), version, Seq(d1), Maybe.empty)((_, args) =>
            value(args(0).asInstanceOf[D1])
        )

    inline def persistent[A, D1](inline id: String)(d1: Task[D1])(
        value: D1 => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        persistent[A, D1](id, TaskVersion.v1)(d1)(value)

    inline def persistent[A, D1, D2](inline id: String, version: TaskVersion)(d1: Task[D1], d2: Task[D2])(
        value: (D1, D2) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        persistentTask[A](TaskId.unsafe(scope.qualify(id).value), version, Seq(d1, d2), Maybe.empty)((_, args) =>
            value(args(0).asInstanceOf[D1], args(1).asInstanceOf[D2])
        )

    inline def persistent[A, D1, D2](inline id: String)(d1: Task[D1], d2: Task[D2])(
        value: (D1, D2) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        persistent[A, D1, D2](id, TaskVersion.v1)(d1, d2)(value)

    inline def persistent[A, D1, D2, D3](
        inline id: String,
        version: TaskVersion
    )(d1: Task[D1], d2: Task[D2], d3: Task[D3])(
        value: (D1, D2, D3) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        persistentTask[A](TaskId.unsafe(scope.qualify(id).value), version, Seq(d1, d2, d3), Maybe.empty)((_, args) =>
            value(args(0).asInstanceOf[D1], args(1).asInstanceOf[D2], args(2).asInstanceOf[D3])
        )

    inline def persistent[A, D1, D2, D3](inline id: String)(d1: Task[D1], d2: Task[D2], d3: Task[D3])(
        value: (D1, D2, D3) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        persistent[A, D1, D2, D3](id, TaskVersion.v1)(d1, d2, d3)(value)

    inline def persistent[A, D1, D2, D3, D4](
        inline id: String,
        version: TaskVersion
    )(d1: Task[D1], d2: Task[D2], d3: Task[D3], d4: Task[D4])(
        value: (D1, D2, D3, D4) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        persistentTask[A](TaskId.unsafe(scope.qualify(id).value), version, Seq(d1, d2, d3, d4), Maybe.empty)(
            (_, args) =>
                value(
                    args(0).asInstanceOf[D1],
                    args(1).asInstanceOf[D2],
                    args(2).asInstanceOf[D3],
                    args(3).asInstanceOf[D4]
                )
        )

    inline def persistent[A, D1, D2, D3, D4](inline id: String)(d1: Task[D1], d2: Task[D2], d3: Task[D3], d4: Task[D4])(
        value: (D1, D2, D3, D4) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        persistent[A, D1, D2, D3, D4](id, TaskVersion.v1)(d1, d2, d3, d4)(value)

    inline def persistent[A, D1, D2, D3, D4, D5](
        inline id: String,
        version: TaskVersion
    )(d1: Task[D1], d2: Task[D2], d3: Task[D3], d4: Task[D4], d5: Task[D5])(
        value: (D1, D2, D3, D4, D5) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        persistentTask[A](TaskId.unsafe(scope.qualify(id).value), version, Seq(d1, d2, d3, d4, d5), Maybe.empty)(
            (_, args) =>
                value(
                    args(0).asInstanceOf[D1],
                    args(1).asInstanceOf[D2],
                    args(2).asInstanceOf[D3],
                    args(3).asInstanceOf[D4],
                    args(4).asInstanceOf[D5]
                )
        )

    inline def persistent[A, D1, D2, D3, D4, D5](
        inline id: String
    )(d1: Task[D1], d2: Task[D2], d3: Task[D3], d4: Task[D4], d5: Task[D5])(
        value: (D1, D2, D3, D4, D5) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        persistent[A, D1, D2, D3, D4, D5](id, TaskVersion.v1)(d1, d2, d3, d4, d5)(value)

    inline def persistent[A, D1, D2, D3, D4, D5, D6](
        inline id: String,
        version: TaskVersion
    )(d1: Task[D1], d2: Task[D2], d3: Task[D3], d4: Task[D4], d5: Task[D5], d6: Task[D6])(
        value: (D1, D2, D3, D4, D5, D6) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        persistentTask[A](TaskId.unsafe(scope.qualify(id).value), version, Seq(d1, d2, d3, d4, d5, d6), Maybe.empty)(
            (_, args) =>
                value(
                    args(0).asInstanceOf[D1],
                    args(1).asInstanceOf[D2],
                    args(2).asInstanceOf[D3],
                    args(3).asInstanceOf[D4],
                    args(4).asInstanceOf[D5],
                    args(5).asInstanceOf[D6]
                )
        )

    inline def persistent[A, D1, D2, D3, D4, D5, D6](
        inline id: String
    )(d1: Task[D1], d2: Task[D2], d3: Task[D3], d4: Task[D4], d5: Task[D5], d6: Task[D6])(
        value: (D1, D2, D3, D4, D5, D6) => A < BodyEffects
    )(using scope: TaskScope, cacheable: Cacheable[A], hashable: Hashable[A]): Task[A] =
        persistent[A, D1, D2, D3, D4, D5, D6](id, TaskVersion.v1)(d1, d2, d3, d4, d5, d6)(value)

    inline def persistent[A, P](inline id: String, version: TaskVersion)(
        value: P => A < BodyEffects
    )(using scope: TaskScope, hp: Hashable[P], cacheable: Cacheable[A], hashable: Hashable[A]): P => Task[A] =
        val taskId = TaskId.unsafe(scope.qualify(id).value)
        (p: P) => persistentTask[A](taskId, version, Nil, Maybe(hp.hash(p)))((_, _) => value(p))

    inline def persistent[A, P](inline id: String)(
        value: P => A < BodyEffects
    )(using scope: TaskScope, hp: Hashable[P], cacheable: Cacheable[A], hashable: Hashable[A]): P => Task[A] =
        persistent[A, P](id, TaskVersion.v1)(value)

    inline def persistent[A, P, D1](inline id: String, version: TaskVersion)(d1: Task[D1])(
        value: (P, D1) => A < BodyEffects
    )(using scope: TaskScope, hp: Hashable[P], cacheable: Cacheable[A], hashable: Hashable[A]): P => Task[A] =
        val taskId = TaskId.unsafe(scope.qualify(id).value)
        (p: P) =>
            persistentTask[A](taskId, version, Seq(d1), Maybe(hp.hash(p)))((_, args) =>
                value(p, args(0).asInstanceOf[D1])
            )

    inline def persistent[A, P, D1](inline id: String)(d1: Task[D1])(
        value: (P, D1) => A < BodyEffects
    )(using scope: TaskScope, hp: Hashable[P], cacheable: Cacheable[A], hashable: Hashable[A]): P => Task[A] =
        persistent[A, P, D1](id, TaskVersion.v1)(d1)(value)

    inline def persistent[A, P, D1, D2](inline id: String, version: TaskVersion)(d1: Task[D1], d2: Task[D2])(
        value: (P, D1, D2) => A < BodyEffects
    )(using scope: TaskScope, hp: Hashable[P], cacheable: Cacheable[A], hashable: Hashable[A]): P => Task[A] =
        val taskId = TaskId.unsafe(scope.qualify(id).value)
        (p: P) =>
            persistentTask[A](taskId, version, Seq(d1, d2), Maybe(hp.hash(p)))((_, args) =>
                value(p, args(0).asInstanceOf[D1], args(1).asInstanceOf[D2])
            )

    inline def persistent[A, P, D1, D2](inline id: String)(d1: Task[D1], d2: Task[D2])(
        value: (P, D1, D2) => A < BodyEffects
    )(using scope: TaskScope, hp: Hashable[P], cacheable: Cacheable[A], hashable: Hashable[A]): P => Task[A] =
        persistent[A, P, D1, D2](id, TaskVersion.v1)(d1, d2)(value)

    inline def persistent[A, P, D1, D2, D3](
        inline id: String,
        version: TaskVersion
    )(d1: Task[D1], d2: Task[D2], d3: Task[D3])(
        value: (P, D1, D2, D3) => A < BodyEffects
    )(using scope: TaskScope, hp: Hashable[P], cacheable: Cacheable[A], hashable: Hashable[A]): P => Task[A] =
        val taskId = TaskId.unsafe(scope.qualify(id).value)
        (p: P) =>
            persistentTask[A](taskId, version, Seq(d1, d2, d3), Maybe(hp.hash(p)))((_, args) =>
                value(p, args(0).asInstanceOf[D1], args(1).asInstanceOf[D2], args(2).asInstanceOf[D3])
            )

    inline def persistent[A, P, D1, D2, D3](inline id: String)(d1: Task[D1], d2: Task[D2], d3: Task[D3])(
        value: (P, D1, D2, D3) => A < BodyEffects
    )(using scope: TaskScope, hp: Hashable[P], cacheable: Cacheable[A], hashable: Hashable[A]): P => Task[A] =
        persistent[A, P, D1, D2, D3](id, TaskVersion.v1)(d1, d2, d3)(value)

    /** Non-cached graph-internal work. Activity values always evaluate once per workflow execution, never read/write
      * cache records, and still produce a meaningful value hash for cached dependents.
      *
      * {{{
      * val seed = Task.activity("seed")(System.currentTimeMillis())
      * val derived = Task.cached("derived")(seed) { millis =>
      *   s"seed=$millis"
      * }
      * }}}
      */
    final class Activity[A] @publicInBinary private[workflow] (
        val id: TaskId,
        val version: TaskVersion,
        depTasks: Seq[Task[?]],
        private[workflow] val value: (TaskContext, IndexedSeq[Any]) => A < BodyEffects,
        private[workflow] val hashable: Hashable[A]
    ) extends Task[A]:
        type ResultType = A
        private[workflow] val deps: Seq[AnyTask] = depTasks.map(AnyTask(_))

    private def activityTask[A](id: TaskId, version: TaskVersion, deps: Seq[Task[?]])(
        value: (TaskContext, IndexedSeq[Any]) => A < BodyEffects
    )(using hashable: Hashable[A]): Activity[A] =
        new Activity[A](id, version, deps, value, hashable)

    inline def activity[A](inline id: String, version: TaskVersion)(
        value: => A < BodyEffects
    )(using scope: TaskScope, hashable: Hashable[A]): Activity[A] =
        activityTask[A](TaskId.unsafe(scope.qualify(id).value), version, Nil)((_, _) => value)

    inline def activity[A](inline id: String)(value: => A < BodyEffects)(using
        scope: TaskScope,
        hashable: Hashable[A]
    ): Activity[A] =
        activity[A](id, TaskVersion.v1)(value)

    inline def activity[A, D1](inline id: String, version: TaskVersion)(d1: Task[D1])(
        value: D1 => A < BodyEffects
    )(using scope: TaskScope, hashable: Hashable[A]): Activity[A] =
        activityTask[A](TaskId.unsafe(scope.qualify(id).value), version, Seq(d1))((_, args) =>
            value(args(0).asInstanceOf[D1])
        )

    inline def activity[A, D1](inline id: String)(d1: Task[D1])(
        value: D1 => A < BodyEffects
    )(using scope: TaskScope, hashable: Hashable[A]): Activity[A] =
        activity[A, D1](id, TaskVersion.v1)(d1)(value)

    inline def activity[A, D1, D2](inline id: String, version: TaskVersion)(d1: Task[D1], d2: Task[D2])(
        value: (D1, D2) => A < BodyEffects
    )(using scope: TaskScope, hashable: Hashable[A]): Activity[A] =
        activityTask[A](TaskId.unsafe(scope.qualify(id).value), version, Seq(d1, d2))((_, args) =>
            value(args(0).asInstanceOf[D1], args(1).asInstanceOf[D2])
        )

    inline def activity[A, D1, D2](inline id: String)(d1: Task[D1], d2: Task[D2])(
        value: (D1, D2) => A < BodyEffects
    )(using scope: TaskScope, hashable: Hashable[A]): Activity[A] =
        activity[A, D1, D2](id, TaskVersion.v1)(d1, d2)(value)

    inline def activity[A, P](inline id: String, version: TaskVersion)(
        value: P => A < BodyEffects
    )(using scope: TaskScope, hashable: Hashable[A]): P => Activity[A] =
        val taskId = TaskId.unsafe(scope.qualify(id).value)
        (p: P) => activityTask[A](taskId, version, Nil)((_, _) => value(p))

    inline def activity[A, P](inline id: String)(
        value: P => A < BodyEffects
    )(using scope: TaskScope, hashable: Hashable[A]): P => Activity[A] =
        activity[A, P](id, TaskVersion.v1)(value)

    inline def activity[A, P, D1](inline id: String, version: TaskVersion)(d1: Task[D1])(
        value: (P, D1) => A < BodyEffects
    )(using scope: TaskScope, hashable: Hashable[A]): P => Activity[A] =
        val taskId = TaskId.unsafe(scope.qualify(id).value)
        (p: P) => activityTask[A](taskId, version, Seq(d1))((_, args) => value(p, args(0).asInstanceOf[D1]))

    inline def activity[A, P, D1](inline id: String)(d1: Task[D1])(
        value: (P, D1) => A < BodyEffects
    )(using scope: TaskScope, hashable: Hashable[A]): P => Activity[A] =
        activity[A, P, D1](id, TaskVersion.v1)(d1)(value)

    inline def activity[A, P, D1, D2](inline id: String, version: TaskVersion)(d1: Task[D1], d2: Task[D2])(
        value: (P, D1, D2) => A < BodyEffects
    )(using scope: TaskScope, hashable: Hashable[A]): P => Activity[A] =
        val taskId = TaskId.unsafe(scope.qualify(id).value)
        (p: P) =>
            activityTask[A](taskId, version, Seq(d1, d2))((_, args) =>
                value(p, args(0).asInstanceOf[D1], args(1).asInstanceOf[D2])
            )

    inline def activity[A, P, D1, D2](inline id: String)(d1: Task[D1], d2: Task[D2])(
        value: (P, D1, D2) => A < BodyEffects
    )(using scope: TaskScope, hashable: Hashable[A]): P => Activity[A] =
        activity[A, P, D1, D2](id, TaskVersion.v1)(d1, d2)(value)

    /** Command kind: always runs (no memoization of own output). Deps still memoize normally.
      *
      * The value receives a [[TaskContext]] just like Cached/Persistent. CLI argument parsing is handled by
      * [[io.eleven19.kymora.workflow.cli.Cli]], which delegates to the parameterized `command[A, P]` variant.
      *
      * `paramHash` does NOT affect command execution (commands never cache their own output), but is carried
      * symmetrically so the field signature stays consistent across kinds.
      */
    final class Command[A] @publicInBinary private[workflow] (
        val id: TaskId,
        val version: TaskVersion,
        depTasks: Seq[Task[?]],
        private[workflow] val value: (TaskContext, IndexedSeq[Any]) => A < BodyEffects,
        private[workflow] val paramHash: Maybe[Fingerprint] = Maybe.empty
    ) extends Task[A]:
        type ResultType = A
        private[workflow] val deps: Seq[AnyTask] = depTasks.map(AnyTask(_))

    /** Build a [[Command]] task under the ambient [[TaskScope]]. Commands always run (their own output is never
      * memoized), but their dependencies still memoize normally.
      */
    // Leaf (no deps)
    inline def command[A](inline id: String, version: TaskVersion)(
        value: => A < BodyEffects
    )(using scope: TaskScope): Command[A] =
        new Command[A](
            TaskId.unsafe(scope.qualify(id).value),
            version,
            Nil,
            (_, _) => value
        )

    // Leaf (default version)
    inline def command[A](inline id: String)(value: => A < BodyEffects)(using scope: TaskScope): Command[A] =
        command[A](id, TaskVersion.v1)(value)

    // 1 dep
    inline def command[A, D1](inline id: String, version: TaskVersion)(
        d1: Task[D1]
    )(body: D1 => A < BodyEffects)(using scope: TaskScope): Command[A] =
        new Command[A](
            TaskId.unsafe(scope.qualify(id).value),
            version,
            Seq(d1),
            (_, args) => body(args(0).asInstanceOf[D1])
        )

    // 1 dep (default version)
    inline def command[A, D1](inline id: String)(
        d1: Task[D1]
    )(body: D1 => A < BodyEffects)(using scope: TaskScope): Command[A] =
        command[A, D1](id, TaskVersion.v1)(d1)(body)

    // 2 deps
    inline def command[A, D1, D2](inline id: String, version: TaskVersion)(
        d1: Task[D1],
        d2: Task[D2]
    )(body: (D1, D2) => A < BodyEffects)(using scope: TaskScope): Command[A] =
        new Command[A](
            TaskId.unsafe(scope.qualify(id).value),
            version,
            Seq(d1, d2),
            (_, args) => body(args(0).asInstanceOf[D1], args(1).asInstanceOf[D2])
        )

    // 2 deps (default version)
    inline def command[A, D1, D2](inline id: String)(
        d1: Task[D1],
        d2: Task[D2]
    )(body: (D1, D2) => A < BodyEffects)(using scope: TaskScope): Command[A] =
        command[A, D1, D2](id, TaskVersion.v1)(d1, d2)(body)

    // ─────────────── parameterized command (P does NOT need Hashable) ──────────
    //
    // Commands never consult or populate the cache, so the parameterized
    // command variants don't require a `Hashable[P]` — `paramHash` remains
    // `Maybe.empty` even with parameters. The factory returns `P => Command[A]`
    // so `Cli.runWith` (and downstream callers) can apply parsed arguments.

    /** Parameterized [[Command]] (leaf). The returned `P => Command[A]` builds a Command whose value closes over the
      * supplied `P`.
      */
    inline def command[A, P](inline id: String, version: TaskVersion)(
        body: P => A < BodyEffects
    )(using scope: TaskScope): P => Command[A] =
        val taskId = TaskId.unsafe(scope.qualify(id).value)
        (p: P) =>
            new Command[A](
                taskId,
                version,
                Nil,
                (_, _) => body(p)
            )

    /** Parameterized [[Command]] (leaf, default version). */
    inline def command[A, P](inline id: String)(
        body: P => A < BodyEffects
    )(using scope: TaskScope): P => Command[A] =
        command[A, P](id, TaskVersion.v1)(body)

    /** Parameterized [[Command]] with 1 dep. */
    inline def command[A, P, D1](inline id: String, version: TaskVersion)(
        d1: Task[D1]
    )(body: (P, D1) => A < BodyEffects)(using scope: TaskScope): P => Command[A] =
        val taskId = TaskId.unsafe(scope.qualify(id).value)
        (p: P) =>
            new Command[A](
                taskId,
                version,
                Seq(d1),
                (_, args) => body(p, args(0).asInstanceOf[D1])
            )

    /** Parameterized [[Command]] with 1 dep (default version). */
    inline def command[A, P, D1](inline id: String)(
        d1: Task[D1]
    )(body: (P, D1) => A < BodyEffects)(using scope: TaskScope): P => Command[A] =
        command[A, P, D1](id, TaskVersion.v1)(d1)(body)

    /** Parameterized [[Command]] with 2 deps. */
    inline def command[A, P, D1, D2](inline id: String, version: TaskVersion)(
        d1: Task[D1],
        d2: Task[D2]
    )(body: (P, D1, D2) => A < BodyEffects)(using
        scope: TaskScope
    ): P => Command[A] =
        val taskId = TaskId.unsafe(scope.qualify(id).value)
        (p: P) =>
            new Command[A](
                taskId,
                version,
                Seq(d1, d2),
                (_, args) => body(p, args(0).asInstanceOf[D1], args(1).asInstanceOf[D2])
            )

    /** Parameterized [[Command]] with 2 deps (default version). */
    inline def command[A, P, D1, D2](inline id: String)(
        d1: Task[D1],
        d2: Task[D2]
    )(body: (P, D1, D2) => A < BodyEffects)(using
        scope: TaskScope
    ): P => Command[A] =
        command[A, P, D1, D2](id, TaskVersion.v1)(d1, d2)(body)
end Task
