package io.eleven19.kymora.workflow.internal

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.store.*
import io.eleven19.kymora.vfs.Vfs
import kyo.*

/** Minimum-viable scheduler for plan Tasks 44-47.
  *
  * Responsibilities:
  *   - Walk a single goal, recursively executing dependencies first.
  *   - Maintain an in-process memo so each `TaskId` is run at most once per
  *     `Workflow.run` invocation (handles diamond DAGs).
  *   - Consult the [[CacheStore]] (via `Env[CacheStore]`) for a per-task
  *     sentinel manifest:
  *       - HIT  -> emit [[WorkflowEvent.TaskCached]] and re-execute the body
  *                 to recover the value (no value serialization yet — see
  *                 the simplification notes in the task brief).
  *       - MISS -> emit [[WorkflowEvent.TaskStarted]], run the body, emit
  *                 [[WorkflowEvent.TaskCompleted]], and write a sentinel
  *                 manifest so the next run is a cache HIT.
  *   - Always emit [[WorkflowEvent.TaskQueued]] (via `Env[Observer]`) when
  *     a node is first visited.
  *   - Honor `cfg.parallelism` at the per-node level: a node's dep list is
  *     resolved via `Async.foreach(..., cfg.parallelism)` when parallelism
  *     > 1 (real fan-out across the whole DAG is a follow-up — see
  *     [[resolveDeps]]).
  *
  * Out of scope for this task (covered by later plan tasks):
  *   - Real `TaskRecord[A]` round-trip serialization (Tasks 48-49) — the
  *     `value` field of the cached manifest is not yet decoded; bodies are
  *     re-executed on cache HIT.
  *   - `cfg.bypass` / `cfg.readOnly` / `cfg.noCache` flag handling beyond
  *     the simplest "skip cache write if readOnly or noCache" path.
  *   - `cfg.continueOnError` enforcement: today the field is observable on
  *     `Workflow.Config` but the scheduler always aborts on the first task
  *     failure. Error accumulation across siblings lands with the full
  *     fan-out parallelism follow-up.
  *   - True fiber-per-node parallelism: `resolveDeps` only bounds concurrency
  *     within a single node's dep list; siblings of independent sub-graphs
  *     are still walked one node at a time at the recursive frontier.
  */
private[workflow] object Scheduler:

  /** Result of executing a node: its materialised value plus a fingerprint
    * of that value (used as a [[Hashing.DepFingerprint]] for downstream
    * inputsHash computations).
    */
  private final case class NodeResult(value: Any, valueHash: Fingerprint)

  /** Resolve a chunk of dependencies, honoring `cfg.parallelism`.
    *
    * For `parallelism <= 1` we fall through to `Kyo.foreach` (sequential).
    * For `parallelism > 1` we use `Async.foreach` with a bounded concurrency
    * cap so independent leaves can run concurrently on the JVM. The memo is
    * an `AtomicRef`, so racing siblings that share a transitive dep stay
    * consistent (last-writer-wins on identical [[NodeResult]] values).
    *
    * Simplification (documented in the file header): real fan-out parallelism
    * across the whole DAG is bounded only by this single-level call. A node
    * that is the only entry to a sub-graph still resolves that sub-graph
    * sequentially at each level. A future Task will lift this to true
    * fiber-per-node scheduling.
    */
  private def resolveDeps(
      deps: Chunk[Task[?]],
      memo: AtomicRef[Map[TaskId, NodeResult]],
      cfg: Workflow.Config,
  )(using
      Frame,
  ): Chunk[NodeResult] < (Async & Workflow.Services & Abort[WorkflowError]) =
    if cfg.parallelism <= 1 || deps.size <= 1 then
      Kyo.foreach(deps)(d => runNode(d, memo))
    else
      Async.foreach(deps, cfg.parallelism)(d => runNode(d, memo))

  /** Execute a single goal under the ambient services. */
  def execute[A](goal: Task[A])(using
      Frame,
  ): A < (Async & Workflow.Services & Abort[WorkflowError]) =
    Graph.collect(Seq(goal)) match
      case Left(err)  => Abort.fail(err)
      case Right(_)   =>
        for
          memo <- AtomicRef.init(Map.empty[TaskId, NodeResult])
          res  <- runNode(goal, memo)
        yield res.value.asInstanceOf[A]

  /** Run a node, consulting the in-process memo first. */
  private def runNode(
      task: Task[?],
      memo: AtomicRef[Map[TaskId, NodeResult]],
  )(using
      Frame,
  ): NodeResult < (Async & Workflow.Services & Abort[WorkflowError]) =
    memo.get.map { current =>
      current.get(task.id) match
        case Some(existing) => (existing: NodeResult)
        case None           =>
          runFreshNode(task, memo).map { result =>
            memo.updateAndGet(_.updated(task.id, result)).andThen(result)
          }
    }

  /** Execute a node from scratch (no memo entry). Dispatches on the task
    * kind.
    */
  private def runFreshNode(
      task: Task[?],
      memo: AtomicRef[Map[TaskId, NodeResult]],
  )(using
      Frame,
  ): NodeResult < (Async & Workflow.Services & Abort[WorkflowError]) =
    for
      observer <- Env.get[Observer]
      cfg      <- Env.get[Workflow.Config]
      _        <- observer.onEvent(WorkflowEvent.TaskQueued(task.id))
      result   <- task match
                    case src: Task.Source        => runSource(src, observer)
                    case in: Task.Input[?]       => runInput(in, observer)
                    case c: Task.Cached[?]       => runCached(c, memo, cfg, observer)
                    case p: Task.Persistent[?]   => runPersistent(p, memo, cfg, observer)
                    case c: Task.Command[?]      => runCommand(c, memo, cfg, observer)
    yield result

  // ---------------------------------------------------------------------
  // Source / Input
  // ---------------------------------------------------------------------

  /** Source nodes: re-evaluated each run. Reads the file contents via the
    * ambient `Env[Vfs]` and produces a [[VPathRef]] whose fingerprint is
    * either the content hash ([[VPathRef.of]]) or the cheaper size+mtime
    * quick hash ([[VPathRef.quick]]), selected by `src.quick`.
    *
    * Downstream tasks depending on the source see the actual content hash
    * change when the file's bytes change — that's how Source invalidates
    * cached downstream tasks.
    */
  private def runSource(
      src: Task.Source,
      observer: Observer,
  )(using Frame): NodeResult < (Async & Env[Vfs] & Abort[WorkflowError]) =
    val started = java.time.Instant.now()
    for
      vfs   <- Env.get[Vfs]
      _     <- observer.onEvent(WorkflowEvent.TaskStarted(src.id, Chunk.empty, started))
      ref   <- runBody[VPathRef](
                 src.id,
                 if src.quick then VPathRef.quick(src.path, vfs)
                 else VPathRef.of(src.path, vfs),
               )
      _     <- observer.onEvent(WorkflowEvent.TaskCompleted(src.id, ref.fingerprint, 0L))
    yield NodeResult(ref, ref.fingerprint)
  end runSource

  /** Input nodes: run the `read` thunk and hash the value via the embedded
    * `Hashable`.
    */
  private def runInput(
      in: Task.Input[?],
      observer: Observer,
  )(using Frame): NodeResult < (Async & Abort[WorkflowError]) =
    val started = java.time.Instant.now()
    for
      _     <- observer.onEvent(WorkflowEvent.TaskStarted(in.id, Chunk.empty, started))
      value <- runBody(in.id, in.read())
      // Hashable.hash takes A, but in.hashable is Hashable[?] — Hashable is
      // contravariant in effect (a hash function), so a cast is safe here.
      vh     = in.hashable.asInstanceOf[Hashable[Any]].hash(value)
      _     <- observer.onEvent(WorkflowEvent.TaskCompleted(in.id, vh, 0L))
    yield NodeResult(value, vh)
  end runInput

  // ---------------------------------------------------------------------
  // Task.Cached — the main path
  // ---------------------------------------------------------------------

  private def runCached(
      task: Task.Cached[?],
      memo: AtomicRef[Map[TaskId, NodeResult]],
      cfg: Workflow.Config,
      observer: Observer,
  )(using
      Frame,
  ): NodeResult < (Async & Workflow.Services & Abort[WorkflowError]) =
    for
      // Run dependencies first, honoring cfg.parallelism (Task 47).
      depResults <- resolveDeps(Chunk.from(task.deps), memo, cfg)
      depArgs     = depResults.toIndexedSeq.map(_.value)
      depFps      = depResults.map(r => Hashing.DepFingerprint(taskIdOf(task.deps, depResults, r), r.valueHash))
      // Compute hashes.
      bodyHash    = Hashing.bodyHash(task.id, task.version)
      expected    = Hashing.inputsHash(task.id, bodyHash, depFps, task.paramHash)
      key         = CacheKey.fromTaskId(task.id)
      // Read the (sentinel) cache entry.
      existing   <- readManifest(key)
      result     <- existing match
                      case kyo.Present(_) if !cfg.noCache && !cfg.bypass.contains(task.id) =>
                        cacheHit(task, depArgs, expected, observer)
                      case _ =>
                        cacheMiss(task, depArgs, depFps, bodyHash, expected, key, cfg, observer)
    yield result
  end runCached

  /** Cache HIT: emit `TaskCached`, re-execute the body to recover the value
    * (no value serialization yet). */
  private def cacheHit(
      task: Task.Cached[?],
      depArgs: IndexedSeq[Any],
      expected: Fingerprint,
      observer: Observer,
  )(using Frame): NodeResult < (Async & Abort[WorkflowError]) =
    for
      _     <- observer.onEvent(WorkflowEvent.TaskCached(task.id, expected))
      ctx    = newTaskContext()
      value <- runBody(task.id, task.body(ctx, depArgs))
      vh     = valueFingerprint(value)
    yield NodeResult(value, vh)

  /** Cache MISS: emit `TaskStarted` + `TaskCompleted`, run the body, write
    * a sentinel manifest so the next run is a HIT. */
  private def cacheMiss(
      task: Task.Cached[?],
      depArgs: IndexedSeq[Any],
      depFps: Chunk[Hashing.DepFingerprint],
      bodyHash: Fingerprint,
      expected: Fingerprint,
      key: CacheKey,
      cfg: Workflow.Config,
      observer: Observer,
  )(using Frame): NodeResult < (Async & Env[CacheStore] & Abort[WorkflowError]) =
    val started = java.time.Instant.now()
    val depIds  = depFps.map(_.id)
    for
      _     <- observer.onEvent(WorkflowEvent.TaskStarted(task.id, depIds, started))
      ctx    = newTaskContext()
      value <- runBody(task.id, task.body(ctx, depArgs))
      vh     = valueFingerprint(value)
      _     <- observer.onEvent(WorkflowEvent.TaskCompleted(task.id, vh, 0L))
      _     <-
        if cfg.readOnly || cfg.noCache then (() : Unit < Async)
        else writeSentinel(key, bodyHash, expected, vh, task.version)
    yield NodeResult(value, vh)

  // ---------------------------------------------------------------------
  // Task.Persistent — mirrors Task.Cached structurally for now
  // ---------------------------------------------------------------------

  /** Persistent semantics differ from Cached only in `.dest/` retention
    * (the directory persists across rebuilds and is re-presented to the
    * body via a per-key advisory lock). The current scheduler does not
    * yet acquire a real workspace (see Scheduler simplification notes),
    * so this branch is structurally identical to [[runCached]]. We use
    * `openPersistentWorkspace` (rather than `openWorkspace`) only as a
    * placeholder for the eventual real wiring; for now no workspace is
    * actually opened in either branch.
    */
  private def runPersistent(
      task: Task.Persistent[?],
      memo: AtomicRef[Map[TaskId, NodeResult]],
      cfg: Workflow.Config,
      observer: Observer,
  )(using
      Frame,
  ): NodeResult < (Async & Workflow.Services & Abort[WorkflowError]) =
    for
      depResults <- resolveDeps(Chunk.from(task.deps), memo, cfg)
      depArgs     = depResults.toIndexedSeq.map(_.value)
      depFps      = depResults.map(r => Hashing.DepFingerprint(taskIdOf(task.deps, depResults, r), r.valueHash))
      bodyHash    = Hashing.bodyHash(task.id, task.version)
      expected    = Hashing.inputsHash(task.id, bodyHash, depFps, task.paramHash)
      key         = CacheKey.fromTaskId(task.id)
      existing   <- readManifest(key)
      result     <- existing match
                      case kyo.Present(_) if !cfg.noCache && !cfg.bypass.contains(task.id) =>
                        persistentCacheHit(task, depArgs, expected, observer)
                      case _ =>
                        persistentCacheMiss(task, depArgs, depFps, bodyHash, expected, key, cfg, observer)
    yield result
  end runPersistent

  private def persistentCacheHit(
      task: Task.Persistent[?],
      depArgs: IndexedSeq[Any],
      expected: Fingerprint,
      observer: Observer,
  )(using Frame): NodeResult < (Async & Abort[WorkflowError]) =
    for
      _     <- observer.onEvent(WorkflowEvent.TaskCached(task.id, expected))
      ctx    = newTaskContext()
      value <- runBody(task.id, task.body(ctx, depArgs))
      vh     = valueFingerprint(value)
    yield NodeResult(value, vh)

  private def persistentCacheMiss(
      task: Task.Persistent[?],
      depArgs: IndexedSeq[Any],
      depFps: Chunk[Hashing.DepFingerprint],
      bodyHash: Fingerprint,
      expected: Fingerprint,
      key: CacheKey,
      cfg: Workflow.Config,
      observer: Observer,
  )(using Frame): NodeResult < (Async & Env[CacheStore] & Abort[WorkflowError]) =
    val started = java.time.Instant.now()
    val depIds  = depFps.map(_.id)
    for
      _     <- observer.onEvent(WorkflowEvent.TaskStarted(task.id, depIds, started))
      ctx    = newTaskContext()
      value <- runBody(task.id, task.body(ctx, depArgs))
      vh     = valueFingerprint(value)
      _     <- observer.onEvent(WorkflowEvent.TaskCompleted(task.id, vh, 0L))
      _     <-
        if cfg.readOnly || cfg.noCache then (() : Unit < Async)
        else writeSentinel(key, bodyHash, expected, vh, task.version)
    yield NodeResult(value, vh)

  // ---------------------------------------------------------------------
  // Task.Command — always runs, never caches its own output
  // ---------------------------------------------------------------------

  /** Commands run their body on every invocation and never consult or
    * populate the cache. Dependencies are still resolved via the normal
    * memoized path, so a single command run reuses dep results across
    * diamond fan-ins but cross-run memoization comes only from the
    * dependencies themselves (Cached/Persistent), not the command.
    *
    * Events emitted: `TaskQueued` (from `runFreshNode`), `TaskStarted`,
    * `TaskCompleted`. Commands never emit `TaskCached`.
    */
  private def runCommand(
      task: Task.Command[?],
      memo: AtomicRef[Map[TaskId, NodeResult]],
      cfg: Workflow.Config,
      observer: Observer,
  )(using
      Frame,
  ): NodeResult < (Async & Workflow.Services & Abort[WorkflowError]) =
    val started = java.time.Instant.now()
    for
      depResults <- resolveDeps(Chunk.from(task.deps), memo, cfg)
      depArgs     = depResults.toIndexedSeq.map(_.value)
      depIds      = depResults.map(r => taskIdOf(task.deps, depResults, r))
      _          <- observer.onEvent(WorkflowEvent.TaskStarted(task.id, depIds, started))
      ctx         = newTaskContext()
      value      <- runBody(task.id, task.body(ctx, depArgs))
      // Command outputs are not consumed by downstream deps (Commands are
      // entry points), so the valueHash is a sentinel rather than a hash
      // of the runtime value.
      vh          = Fingerprint.unsafe("command:nostore")
      _          <- observer.onEvent(WorkflowEvent.TaskCompleted(task.id, vh, 0L))
    yield NodeResult(value, vh)
  end runCommand

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  private def runBody[A](
      id: TaskId,
      body: => A < (Async & Abort[Throwable]),
  )(using Frame): A < (Async & Abort[WorkflowError]) =
    Abort.recover[Throwable](t =>
      Abort.fail[WorkflowError](WorkflowError.TaskFailed(id, Option(t.getMessage).getOrElse(t.toString)))
    )(body)

  private def newTaskContext()(using Frame): TaskContext =
    TaskContext(dest = io.eleven19.kymora.vfs.VPath(""))

  /** Best-effort fingerprint of a runtime value. Uses `Schema[String]` on
    * the `value.toString` so we always get a deterministic-ish hash without
    * requiring per-task `Schema[A]` threading. This is intentionally weak —
    * proper value hashing arrives with the `Hashable[A]` threading work in
    * Task 45+.
    */
  private def valueFingerprint(value: Any): Fingerprint =
    Fingerprint.ofBytes(Chunk.from(value.toString.getBytes))

  /** Look up `taskId` of a result by reference identity in the original
    * dep list (positional). Both lists have the same length and order.
    */
  private def taskIdOf(
      deps: Seq[Task[?]],
      results: Chunk[NodeResult],
      target: NodeResult,
  ): TaskId =
    val idx = results.indexOf(target)
    deps(idx).id

  private def readManifest(
      key: CacheKey,
  )(using Frame): Maybe[StoredManifest] < (Async & Env[CacheStore] & Abort[WorkflowError]) =
    for
      store <- Env.get[CacheStore]
      r     <- Abort.recover[StoreError](e => Abort.fail[WorkflowError](WorkflowError.Store(e))):
                 store.read(key)
    yield r

  /** Write a minimal sentinel manifest — currently just the inputs hash
    * encoded as raw UTF-8 bytes. Future work (plan Tasks 48-49) replaces
    * this with a proper `TaskRecord[A]` JSON envelope.
    */
  private def writeSentinel(
      key: CacheKey,
      bodyHash: Fingerprint,
      inputsHash: Fingerprint,
      valueHash: Fingerprint,
      version: TaskVersion,
  )(using Frame): Unit < (Async & Env[CacheStore] & Abort[WorkflowError]) =
    val payload =
      s"sentinel|v=${version.render}|body=${bodyHash.value}|inputs=${inputsHash.value}|value=${valueHash.value}"
    val bytes = Chunk.from(payload.getBytes)
    for
      store <- Env.get[CacheStore]
      _     <- Abort.recover[StoreError](e => Abort.fail[WorkflowError](WorkflowError.Store(e))):
                 store.write(key, bytes, Maybe.empty)
    yield ()

end Scheduler
