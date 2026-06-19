package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.internal.CacheLayout
import io.eleven19.kymora.workflow.internal.Scheduler
import io.eleven19.kymora.workflow.internal.Validation
import io.eleven19.kymora.workflow.store.CacheKey
import io.eleven19.kymora.vfs.*
import kyo.*
import kyo.kernel.ContextEffect

/** Kyo effect for executing workflow tasks.
  *
  * `Workflow` is the public execution effect for `kymora-workflow`. Programs describe task execution with
  * [[Workflow.run]], [[Workflow.runAll]], or the shorthand `task()`. Callers provide a [[Workflow.Runtime]] with
  * [[Workflow.handle]].
  *
  * {{{
  * val compile = Task.cached("compile") {
  *     "compiled"
  * }
  *
  * val program =
  *     for
  *         backend <- Vfs.inMemory.init
  *         runtime = Workflow.Runtime(backend)
  *         result <- Workflow.handle(runtime)(compile())
  *     yield result
  * }}}
  */
sealed trait Workflow extends ContextEffect[Workflow.Context]

object Workflow:

    /** Concrete runtime dependencies used to handle the [[Workflow]] effect.
      *
      * `config` is pure run policy. `vfs`, `cacheRoot`, `observer`, and `codec` are runtime capabilities selected by
      * the host application. Use `Runtime(vfs)` when you already have a backend, or [[Runtime.default]] for an
      * in-memory runtime with the default cache root and silent observer.
      */
    final case class Runtime(
        config: Workflow.Config = Workflow.Config.default,
        vfs: Vfs.Backend,
        cacheRoot: VPath = VPath("cache"),
        observer: Observer = Observer.NoOp,
        codec: Codec = Json()
    )

    object Runtime:

        def apply(vfs: Vfs.Backend): Runtime =
            Runtime(
                config = Config.default,
                vfs = vfs,
                cacheRoot = VPath("cache"),
                observer = Observer.NoOp,
                codec = Json()
            )

        def default(using Frame): Runtime < Sync =
            Vfs.inMemory.init.map(vfs => Runtime(vfs))
    end Runtime

    /** Ambient workflow context.
      *
      * User code usually accesses this through [[runtime]], [[context]], and [[dest]] rather than constructing it
      * directly.
      */
    final case class Context(runtime: Runtime, task: Maybe[TaskContext])

    /** Pure run-level configuration.
      *
      * This type intentionally contains no services or codecs. Operational dependencies live on [[Runtime]].
      */
    final case class Config(
        parallelism: Int,
        bypass: Set[TaskId] = Set.empty,
        readOnly: Boolean = false,
        noCache: Boolean = false,
        continueOnError: Boolean = false,
        verifyDest: Boolean = false
    )

    object Config:

        def default: Config =
            Config(
                parallelism = math.max(2, java.lang.Runtime.getRuntime.availableProcessors - 1)
            )
    end Config

    /** Throwable bridge for code that must cross a non-Kyo exception boundary. */
    final class WorkflowException(val error: WorkflowError) extends RuntimeException(error.toString)

    inline def scope[A](inline prefix: String)(body: TaskScope ?=> A)(using
        outer: TaskScope
    ): A =
        body(using outer.qualify(TaskScope(prefix).value))

    def scopeWith[A](prefix: String)(body: TaskScope ?=> A)(using
        outer: TaskScope
    ): Result[Validation.Reason, A] =
        TaskScope.parse(prefix).map(s => body(using outer.qualify(s.value)))

    def get(using Frame): Context < Workflow =
        ContextEffect.suspend(Tag[Workflow])

    def runtime(using Frame): Runtime < Workflow =
        get.map(_.runtime)

    def context(using Frame): TaskContext < (Workflow & Abort[WorkflowError]) =
        get.flatMap {
            case Context(_, Present(ctx)) => ctx
            case _ =>
                Abort.fail(
                    WorkflowError.TaskFailed(TaskId.unsafe("workflow"), "TaskContext is only available inside a task")
                )
        }

    def dest(using Frame): VPath < (Workflow & Abort[WorkflowError]) =
        context.map(_.dest)

    def handle[A, S](runtime: Runtime)(value: A < (Workflow & Vfs & ReadonlyVfs & S))(using Frame): A < S =
        val ctx = Context(runtime, Absent)
        Vfs.run(runtime.vfs):
            ContextEffect.handle(Tag[Workflow], ctx)(value)

    private[workflow] def withTaskContext[A, S](
        taskContext: TaskContext
    )(value: A < (Workflow & S))(using Frame): A < (Workflow & S) =
        get.flatMap { current =>
            ContextEffect.handle(Tag[Workflow], current.copy(task = Maybe(taskContext)))(value)
        }

    def run[A](goal: Task[A])(using
        Frame
    ): A < (Async & Workflow & Abort[WorkflowError]) =
        Scheduler.execute(goal)

    def runAll[A](goals: Task[A]*)(using
        Frame
    ): Chunk[A] < (Async & Workflow & Abort[WorkflowError]) =
        Scheduler.executeAll(Chunk.from(goals))

    def purge()(using Frame): Unit < (Async & Workflow & Abort[WorkflowError]) =
        for
            rt <- runtime
            _  <- CacheLayout(rt.cacheRoot).purge(rt.vfs)
        yield ()

    def clean(prefix: String)(using Frame): Unit < (Async & Workflow & Abort[WorkflowError]) =
        for
            rt   <- runtime
            keys <- CacheLayout(rt.cacheRoot).list(rt.vfs, prefix)
            _    <- Kyo.foreach(keys)(key => CacheLayout(rt.cacheRoot).remove(rt.vfs, key))
        yield ()
end Workflow
