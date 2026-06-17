package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.internal.Scheduler
import io.eleven19.kymora.workflow.internal.Validation
import io.eleven19.kymora.workflow.store.*
import io.eleven19.kymora.vfs.*
import kyo.*

object Workflow:

  /** Aggregate type of every Kyo `Env` effect the engine reads from.
    *
    * Read as "the services the workflow runtime depends on". The
    * `Workflow.Config` value carries pure run-level configuration
    * (parallelism, flags, codec); the other three are services with
    * lifecycle:
    *   - [[Vfs]] — source content / quick hashing
    *   - [[CacheStore]] — cache reads + writes
    *   - [[Observer]] — `WorkflowEvent` emission
    *
    * Concrete call sites use this alias verbatim:
    * {{{
    *   def run[A](goal: Task[A]): A < (Async & Workflow.Services & Abort[WorkflowError])
    * }}}
    *
    * See [[Workflow.Services.provide]] for chaining the four `Env.run`
    * layers in one call, [[Workflow.Services.layer]] for per-service
    * helpers when overriding just one, and [[Workflow.Services.default]]
    * for a sensible default bundle.
    */
  type Services = Env[Workflow.Config] & Env[Vfs] & Env[CacheStore] & Env[Observer]

  /** Definition-scope helper.
    *
    * Compile-time validated literal prefix. Threads a using TaskScope so all
    * Task.init / Task.source / Task.input / Task.command invocations inside
    * `body` see the qualified prefix.
    *
    * Resource scopes (cache lock, observer session, etc.) use Kyo's Scope
    * effect separately — see Workflow.run.
    */
  inline def scope[A](inline prefix: String)(body: TaskScope ?=> A)(using
      outer: TaskScope,
  ): A =
    body(using outer.qualify(TaskScope(prefix).value))

  /** Runtime-parsed prefix variant. Returns Result.fail on invalid input. */
  def scopeWith[A](prefix: String)(body: TaskScope ?=> A)(using
      outer: TaskScope,
  ): Result[Validation.Reason, A] =
    TaskScope.parse(prefix).map(s => body(using outer.qualify(s.value)))

  /** Pure run-level configuration. Injected via `Env[Workflow.Config]`.
    *
    * Service-like dependencies (cache store, observer, VFS) are NOT on
    * Config — they're separate Env effects, see [[Workflow.Services]].
    *
    * Fields:
    *   - codec — blob codec used by the (future) value-round-trip path
    *   - parallelism — fan-out cap inside a single node's dep list (real
    *     fiber-per-node DAG fan-out is tracked in issue #5 §6)
    *   - bypass — task IDs whose cache is forcibly invalidated for the run
    *   - readOnly — never write to the cache, even on MISS
    *   - noCache — never consult the cache; always re-run
    *   - continueOnError — accumulate sibling failures rather than fail-fast
    *     (currently observable but not enforced — issue #5 §7)
    *   - verifyDest — round-trip the workspace dir through the cache store
    *     after a body completes (defensive integrity check)
    */
  final case class Config(
      codec: Codec,
      parallelism: Int,
      bypass: Set[TaskId] = Set.empty,
      readOnly: Boolean = false,
      noCache: Boolean = false,
      continueOnError: Boolean = false,
      verifyDest: Boolean = false,
  )

  object Config:
    /** Sensible default config: `Json()` codec, parallelism = max(2, cores-1). */
    def default: Config =
      Config(
        codec       = Json(),
        parallelism = math.max(2, java.lang.Runtime.getRuntime.availableProcessors - 1),
      )
  end Config

  /** Companion of the [[Services]] type alias — builders, defaults, and
    * per-service layer helpers.
    *
    * The four services are constructed independently and bundled via
    * [[Services.Bundle]]; [[Services.provide]] then layers all four onto
    * an effect requiring `Workflow.Services`. Tests that want to override
    * only one service can call [[Services.layer]] directly instead.
    */
  object Services:

    /** A bundle of the four wired services. Held together so a single
      * [[Services.provide]] call layers them all. */
    final case class Bundle(
        config: Config,
        vfs: Vfs,
        store: CacheStore,
        observer: Observer,
    )

    /** Construct a bundle from explicit pieces. Use when wiring production
      * services that don't fit the [[default]] shape. */
    def init(config: Config, vfs: Vfs, store: CacheStore, observer: Observer): Bundle =
      Bundle(config, vfs, store, observer)

    /** Default bundle: in-memory [[Vfs]], [[VfsDirStore]] rooted at
      * `cache/`, [[ConsoleObserver]], and [[Config.default]]. Suitable as
      * a starting point for examples and tests; replace individual fields
      * via `.copy(...)` to override. */
    def default(using Frame): Bundle < (Async & Abort[StoreError]) =
      for
        vfs   <- Vfs.inMemory.init
        store <- VfsDirStore.init(VPath("cache"), vfs)
      yield Bundle(Config.default, vfs, store, ConsoleObserver)

    /** Per-service `Env.run` helpers.
      *
      * Use when overriding a single service rather than rebuilding the
      * whole bundle:
      * {{{
      *   import Workflow.Services.layer
      *   layer.config(myConfig):
      *     layer.vfs(myVfs):
      *       layer.store(myStore):
      *         layer.observer(myObs):
      *           Workflow.run(goal)
      * }}}
      */
    object layer:
      def config[A, S](c: Config)(eff: A < (S & Env[Config]))(using Frame): A < S =
        Env.run(c)(eff)

      def vfs[A, S](v: Vfs)(eff: A < (S & Env[Vfs]))(using Frame): A < S =
        Env.run(v)(eff)

      def store[A, S](s: CacheStore)(eff: A < (S & Env[CacheStore]))(using Frame): A < S =
        Env.run(s)(eff)

      def observer[A, S](o: Observer)(eff: A < (S & Env[Observer]))(using Frame): A < S =
        Env.run(o)(eff)
    end layer

    /** Layer every service from a [[Bundle]] onto `eff`. The result no
      * longer requires any of [[Workflow.Services]]. */
    def provide[A, S](bundle: Bundle)(eff: A < (S & Services))(using Frame): A < S =
      layer.config(bundle.config) {
        layer.vfs(bundle.vfs) {
          layer.store(bundle.store) {
            layer.observer(bundle.observer)(eff)
          }
        }
      }

  end Services

  /** Execute a single goal task under the ambient [[Workflow.Services]]. */
  def run[A](goal: Task[A])(using
      Frame,
  ): A < (Async & Services & Abort[WorkflowError]) =
    Scheduler.execute(goal)

  /** Execute multiple goal tasks in declaration order under the ambient
    * [[Workflow.Services]] and return their materialised values. */
  def runAll[A](goals: Task[A]*)(using
      Frame,
  ): Chunk[A] < (Async & Services & Abort[WorkflowError]) =
    Kyo.foreach(Chunk.from(goals))(g => Scheduler.execute(g))

  /** Wipe the entire cache. Requires the ambient `Env[CacheStore]`. */
  def purge()(using Frame): Unit < (Async & Env[CacheStore] & Abort[StoreError]) =
    for
      store <- Env.get[CacheStore]
      _     <- store.purge()
    yield ()

  /** Remove cache entries whose key matches the given prefix. Requires the
    * ambient `Env[CacheStore]`. */
  def clean(prefix: String)(using Frame): Unit < (Async & Env[CacheStore] & Abort[StoreError]) =
    for
      store <- Env.get[CacheStore]
      keys  <- store.list(prefix)
      _     <- Kyo.foreach(keys)(store.remove)
    yield ()
end Workflow
