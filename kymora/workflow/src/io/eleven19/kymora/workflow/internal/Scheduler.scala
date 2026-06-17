package io.eleven19.kymora.workflow.internal

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.store.*
import kyo.*

/** Minimum-viable sequential scheduler for plan Task 44.
  *
  * Responsibilities:
  *   - Walk a single goal, recursively executing dependencies first.
  *   - Maintain an in-process memo so each `TaskId` is run at most once per
  *     `Workflow.run` invocation (handles diamond DAGs).
  *   - Consult the [[CacheStore]] for a per-task sentinel manifest:
  *       - HIT  -> emit [[WorkflowEvent.TaskCached]] and re-execute the body
  *                 to recover the value (no value serialization yet — see the
  *                 simplification notes in the task brief).
  *       - MISS -> emit [[WorkflowEvent.TaskStarted]], run the body, emit
  *                 [[WorkflowEvent.TaskCompleted]], and write a sentinel
  *                 manifest so the next run is a cache HIT.
  *   - Always emit [[WorkflowEvent.TaskQueued]] when a node is first visited.
  *
  * Out of scope for this task (covered by later plan tasks):
  *   - Parallel execution / fan-out (Task 47).
  *   - `Task.Persistent` semantics (Task 45).
  *   - `Task.Command` semantics (Task 46).
  *   - Real `TaskRecord[A]` round-trip serialization (Tasks 48-49) — the
  *     `value` field of the cached manifest is not yet decoded; bodies are
  *     re-executed on cache HIT.
  *   - `cfg.bypass` / `cfg.readOnly` / `cfg.noCache` / `cfg.continueOnError`
  *     flag handling beyond the simplest "skip cache write if readOnly or
  *     noCache" path.
  */
private[workflow] object Scheduler:

  /** Result of executing a node: its materialised value plus a fingerprint
    * of that value (used as a [[Hashing.DepFingerprint]] for downstream
    * inputsHash computations).
    */
  private final case class NodeResult(value: Any, valueHash: Fingerprint)

  /** Execute a single goal under the ambient `Workflow.Config`. */
  def execute[A](goal: Task[A])(using
      Frame,
  ): A < (Async & Env[Workflow.Config] & Abort[WorkflowError]) =
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
  )(using Frame): NodeResult < (Async & Env[Workflow.Config] & Abort[WorkflowError]) =
    memo.get.map { current =>
      current.get(task.id) match
        case Some(existing) => (existing: NodeResult)
        case None           =>
          runFreshNode(task, memo).map { result =>
            memo.updateAndGet(_.updated(task.id, result)).andThen(result)
          }
    }

  /** Execute a node from scratch (no memo entry). Dispatches on the task
    * kind. Command is not implemented in this task — it surfaces a
    * `WorkflowError.TaskFailed` describing the gap.
    */
  private def runFreshNode(
      task: Task[?],
      memo: AtomicRef[Map[TaskId, NodeResult]],
  )(using Frame): NodeResult < (Async & Env[Workflow.Config] & Abort[WorkflowError]) =
    Env.use[Workflow.Config] { cfg =>
      for
        _      <- cfg.reporter.onEvent(WorkflowEvent.TaskQueued(task.id))
        result <- task match
                    case src: Task.Source        => runSource(src, cfg)
                    case in: Task.Input[?]       => runInput(in, cfg)
                    case c: Task.Cached[?]       => runCached(c, memo, cfg)
                    case p: Task.Persistent[?]   => runPersistent(p, memo, cfg)
                    case _: Task.Command[?]      =>
                      Abort.fail[WorkflowError](WorkflowError.TaskFailed(
                        task.id,
                        "Task.Command execution not yet implemented (plan Task 46).",
                      ))
      yield result
    }

  // ---------------------------------------------------------------------
  // Source / Input
  // ---------------------------------------------------------------------

  /** Source nodes: re-evaluated each run. In this initial slice we do not
    * touch the VFS — we use a sentinel valueHash derived from the path
    * string. Real content/quick hashing via `VPathRef.of` / `.quick` lands
    * alongside the VFS-injection work in plan Task 45+.
    */
  private def runSource(
      src: Task.Source,
      cfg: Workflow.Config,
  )(using Frame): NodeResult < (Async & Abort[WorkflowError]) =
    val started = java.time.Instant.now()
    val pathHash =
      Fingerprint.ofBytes(Chunk.from(s"source:${src.path.show}".getBytes))
    for
      _ <- cfg.reporter.onEvent(WorkflowEvent.TaskStarted(src.id, Chunk.empty, started))
      _ <- cfg.reporter.onEvent(WorkflowEvent.TaskCompleted(src.id, pathHash, 0L))
    yield NodeResult(VPathRef(src.path, pathHash, quick = src.quick), pathHash)
  end runSource

  /** Input nodes: run the `read` thunk and hash the value via the embedded
    * `Hashable`.
    */
  private def runInput(
      in: Task.Input[?],
      cfg: Workflow.Config,
  )(using Frame): NodeResult < (Async & Abort[WorkflowError]) =
    val started = java.time.Instant.now()
    for
      _     <- cfg.reporter.onEvent(WorkflowEvent.TaskStarted(in.id, Chunk.empty, started))
      value <- runBody(in.id, in.read())
      // Hashable.hash takes A, but in.hashable is Hashable[?] — Hashable is
      // contravariant in effect (a hash function), so a cast is safe here.
      vh     = in.hashable.asInstanceOf[Hashable[Any]].hash(value)
      _     <- cfg.reporter.onEvent(WorkflowEvent.TaskCompleted(in.id, vh, 0L))
    yield NodeResult(value, vh)
  end runInput

  // ---------------------------------------------------------------------
  // Task.Cached — the main path
  // ---------------------------------------------------------------------

  private def runCached(
      task: Task.Cached[?],
      memo: AtomicRef[Map[TaskId, NodeResult]],
      cfg: Workflow.Config,
  )(using Frame): NodeResult < (Async & Env[Workflow.Config] & Abort[WorkflowError]) =
    for
      // Run dependencies first (sequentially for now — parallelism is Task 47).
      depResults <- Kyo.foreach(Chunk.from(task.deps))(d => runNode(d, memo))
      depArgs     = depResults.toIndexedSeq.map(_.value)
      depFps      = depResults.map(r => Hashing.DepFingerprint(taskIdOf(task.deps, depResults, r), r.valueHash))
      // Compute hashes.
      bodyHash    = Hashing.bodyHash(task.id, task.version)
      expected    = Hashing.inputsHash(task.id, bodyHash, depFps)
      key         = CacheKey.fromTaskId(task.id)
      // Read the (sentinel) cache entry.
      existing   <- readManifest(cfg, key)
      result     <- existing match
                      case kyo.Present(_) if !cfg.noCache && !cfg.bypass.contains(task.id) =>
                        cacheHit(task, depArgs, expected, cfg)
                      case _ =>
                        cacheMiss(task, depArgs, depFps, bodyHash, expected, key, cfg)
    yield result
  end runCached

  /** Cache HIT: emit `TaskCached`, re-execute the body to recover the value
    * (no value serialization yet). */
  private def cacheHit(
      task: Task.Cached[?],
      depArgs: IndexedSeq[Any],
      expected: Fingerprint,
      cfg: Workflow.Config,
  )(using Frame): NodeResult < (Async & Abort[WorkflowError]) =
    for
      _     <- cfg.reporter.onEvent(WorkflowEvent.TaskCached(task.id, expected))
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
  )(using Frame): NodeResult < (Async & Abort[WorkflowError]) =
    val started = java.time.Instant.now()
    val depIds  = depFps.map(_.id)
    for
      _     <- cfg.reporter.onEvent(WorkflowEvent.TaskStarted(task.id, depIds, started))
      ctx    = newTaskContext()
      value <- runBody(task.id, task.body(ctx, depArgs))
      vh     = valueFingerprint(value)
      _     <- cfg.reporter.onEvent(WorkflowEvent.TaskCompleted(task.id, vh, 0L))
      _     <-
        if cfg.readOnly || cfg.noCache then (() : Unit < Async)
        else writeSentinel(cfg, key, bodyHash, expected, vh, task.version)
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
  )(using Frame): NodeResult < (Async & Env[Workflow.Config] & Abort[WorkflowError]) =
    for
      depResults <- Kyo.foreach(Chunk.from(task.deps))(d => runNode(d, memo))
      depArgs     = depResults.toIndexedSeq.map(_.value)
      depFps      = depResults.map(r => Hashing.DepFingerprint(taskIdOf(task.deps, depResults, r), r.valueHash))
      bodyHash    = Hashing.bodyHash(task.id, task.version)
      expected    = Hashing.inputsHash(task.id, bodyHash, depFps)
      key         = CacheKey.fromTaskId(task.id)
      existing   <- readManifest(cfg, key)
      result     <- existing match
                      case kyo.Present(_) if !cfg.noCache && !cfg.bypass.contains(task.id) =>
                        persistentCacheHit(task, depArgs, expected, cfg)
                      case _ =>
                        persistentCacheMiss(task, depArgs, depFps, bodyHash, expected, key, cfg)
    yield result
  end runPersistent

  private def persistentCacheHit(
      task: Task.Persistent[?],
      depArgs: IndexedSeq[Any],
      expected: Fingerprint,
      cfg: Workflow.Config,
  )(using Frame): NodeResult < (Async & Abort[WorkflowError]) =
    for
      _     <- cfg.reporter.onEvent(WorkflowEvent.TaskCached(task.id, expected))
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
  )(using Frame): NodeResult < (Async & Abort[WorkflowError]) =
    val started = java.time.Instant.now()
    val depIds  = depFps.map(_.id)
    for
      _     <- cfg.reporter.onEvent(WorkflowEvent.TaskStarted(task.id, depIds, started))
      ctx    = newTaskContext()
      value <- runBody(task.id, task.body(ctx, depArgs))
      vh     = valueFingerprint(value)
      _     <- cfg.reporter.onEvent(WorkflowEvent.TaskCompleted(task.id, vh, 0L))
      _     <-
        if cfg.readOnly || cfg.noCache then (() : Unit < Async)
        else writeSentinel(cfg, key, bodyHash, expected, vh, task.version)
    yield NodeResult(value, vh)

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
    TaskContext(
      dest  = io.eleven19.kymora.vfs.VPath(""),
      emit  = (_: Any) => (),
      clock = Clock.live,
    )

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
      cfg: Workflow.Config,
      key: CacheKey,
  )(using Frame): Maybe[StoredManifest] < (Async & Abort[WorkflowError]) =
    Abort.recover[StoreError](e => Abort.fail[WorkflowError](WorkflowError.Store(e))):
      cfg.store.read(key)

  /** Write a minimal sentinel manifest — currently just the inputs hash
    * encoded as raw UTF-8 bytes. Future work (plan Tasks 48-49) replaces
    * this with a proper `TaskRecord[A]` JSON envelope.
    */
  private def writeSentinel(
      cfg: Workflow.Config,
      key: CacheKey,
      bodyHash: Fingerprint,
      inputsHash: Fingerprint,
      valueHash: Fingerprint,
      version: TaskVersion,
  )(using Frame): Unit < (Async & Abort[WorkflowError]) =
    val payload =
      s"sentinel|v=${version.render}|body=${bodyHash.value}|inputs=${inputsHash.value}|value=${valueHash.value}"
    val bytes = Chunk.from(payload.getBytes)
    Abort.recover[StoreError](e => Abort.fail[WorkflowError](WorkflowError.Store(e))):
      cfg.store.write(key, bytes, Maybe.empty)

end Scheduler
