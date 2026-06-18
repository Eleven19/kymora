package io.eleven19.kymora.workflow.internal

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.store.*
import io.eleven19.kymora.vfs.Vfs
import kyo.*

/** Worklist scheduler with a dedicated planning phase.
  *
  * Responsibilities:
  *   - **Planning phase** — walk all goals once via [[Graph.collect]], build an [[ExecutionPlan]] (reachable tasks,
  *     dependency edges, stable execution order, goal order), then execute from a shared memo.
  *   - **Worklist execution** — schedule every task whose dependencies are satisfied; when a task finishes, newly-ready
  *     dependents are scheduled. A global [[Meter]] semaphore caps in-flight task values and cache I/O at
  *     `max(1, cfg.parallelism)` (Source/Input reads count toward the cap).
  *   - Maintain an in-process memo so each `TaskId` runs or decodes at most once per `executeAll` invocation (handles
  *     diamond DAGs).
  *   - Consult the runtime VFS cache layout for typed task records:
  *     - HIT -> emit [[WorkflowEvent.TaskCached]] and decode the stored value without evaluating the task value again.
  *     - MISS -> emit [[WorkflowEvent.TaskStarted]], evaluate the value, emit [[WorkflowEvent.TaskCompleted]], and
  *       write a typed record.
  *   - Always emit [[WorkflowEvent.TaskQueued]] when a node is first scheduled.
  */
private[workflow] object Scheduler:

    /** Result of executing a node: its materialised value plus a fingerprint of that value (used as a
      * [[Hashing.DepFingerprint]] for downstream inputsHash computations).
      */
    final private case class NodeResult(value: Any, valueHash: Fingerprint)

    /** Output of the planning phase: every reachable task and the edges needed for worklist scheduling.
      *
      * @param tasks
      *   all reachable tasks keyed by id
      * @param deps
      *   direct dependency ids per task (declaration order preserved)
      * @param goalIds
      *   goal task ids in caller declaration order
      * @param order
      *   stable execution order: dependency-before-dependent, goals in caller order (DFS postorder)
      */
    final private case class ExecutionPlan(
        tasks: Map[TaskId, AnyTask],
        deps: Map[TaskId, Chunk[TaskId]],
        goalIds: Chunk[TaskId],
        order: Chunk[TaskId]
    )

    /** Scheduling state for one `executeAll` run — memoised results plus the in-flight set used to avoid
      * double-scheduling under concurrent completion.
      */
    final private case class ExecState(
        results: Map[TaskId, NodeResult] = Map.empty,
        running: Set[TaskId] = Set.empty,
        claimed: Chunk[TaskId] = Chunk.empty
    )

    /** Planning phase: collect the reachable graph and derive dependency edges.
      */
    private def plan(
        goals: Chunk[Task[?]]
    ): Result[WorkflowError, ExecutionPlan] =
        val anyGoals = goals.map(AnyTask(_))
        Graph.collect(anyGoals.toSeq).map { tasks =>
            val deps = tasks.map((id, task) => id -> Graph.depIdsOf(task))
            ExecutionPlan(
                tasks = tasks,
                deps = deps,
                goalIds = goals.map(_.id),
                order = stableOrder(anyGoals)
            )
        }
    end plan

    /** DFS postorder over goals: dependencies before dependents, each id once, goals visited in caller declaration
      * order.
      */
    private def stableOrder(
        goals: Chunk[AnyTask]
    ): Chunk[TaskId] =
        val visited = scala.collection.mutable.Set.empty[TaskId]
        val result  = Chunk.newBuilder[TaskId]

        def visit(task: AnyTask): Unit =
            if visited.contains(task.id) then ()
            else
                Graph.depsOf(task).foreach(visit)
                val _ = visited.add(task.id)
                result.addOne(task.id)
        end visit

        goals.foreach(visit)
        result.result()
    end stableOrder

    /** Execute multiple goals under one plan and shared memo; results follow `goals` declaration order.
      */
    def executeAll[A](goals: Chunk[Task[A]])(using
        Frame
    ): Chunk[A] < (Async & Workflow & Abort[WorkflowError]) =
        for
            plan <- Abort.get(plan(Chunk.from(goals)))
            vals <- Abort.recover[Closed](_ =>
                Abort.fail(WorkflowError.TaskCancelled(TaskId.unsafe("scheduler"), "scope closed"))
            ) {
                Scope.run {
                    for
                        rt <- Workflow.runtime
                        cfg = rt.config
                        meter <- Meter.initSemaphore(concurrency = math.max(1, cfg.parallelism))
                        state <- AtomicRef.init(ExecState())
                        _     <- scheduleReady(plan, state, meter)
                        vals  <- Kyo.foreach(plan.goalIds)(id => state.get.map(_.results(id).value.asInstanceOf[A]))
                    yield Chunk.from(vals)
                }
            }
        yield vals

    /** Execute a single goal under the ambient [[Workflow]] runtime. */
    def execute[A](goal: Task[A])(using
        Frame
    ): A < (Async & Workflow & Abort[WorkflowError]) =
        executeAll(Chunk(goal)).map(_.head)

    /** Claim every currently-ready task atomically and launch it under the global concurrency cap. Completing tasks
      * re-enter the worklist.
      */
    private def scheduleReady(
        plan: ExecutionPlan,
        state: AtomicRef[ExecState],
        meter: Meter
    )(using
        Frame
    ): Unit < (Async & Scope & Sync & Workflow & Abort[WorkflowError | Closed]) =
        for
            rt <- Workflow.runtime
            cfg   = rt.config
            limit = math.max(1, cfg.parallelism)
            ready <- claimReady(plan, state, limit)
            _ <- Async.foreach(ready, limit) { id =>
                meter.run(runPlannedNode(plan, id, state)).map { result =>
                    for
                        _ <- state.updateAndGet { st =>
                            st.copy(
                                results = st.results.updated(id, result),
                                running = st.running - id
                            )
                        }.unit
                        _ <- scheduleReady(plan, state, meter)
                    yield ()
                }
            }
        yield ()
    end scheduleReady

    /** Atomically select up to `limit` ready tasks in stable plan order and mark them running so concurrent schedulers
      * cannot claim the same id.
      */
    private def claimReady(
        plan: ExecutionPlan,
        state: AtomicRef[ExecState],
        limit: Int
    ): Chunk[TaskId] < Sync =
        state
            .updateAndGet { st =>
                val readyBuilder = Chunk.newBuilder[TaskId]
                var claimed      = 0
                plan.order.foreach { id =>
                    if claimed < limit &&
                        plan.tasks.contains(id) &&
                        !st.results.contains(id) &&
                        !st.running.contains(id) &&
                        plan.deps(id).forall(d => st.results.contains(d))
                    then
                        readyBuilder.addOne(id)
                        claimed += 1
                }
                val ready = readyBuilder.result()
                st.copy(
                    running = st.running ++ ready,
                    claimed = ready
                )
            }
            .map(_.claimed)

    /** Run one planned node: emit `TaskQueued`, resolve deps from the memo, and dispatch on task kind.
      */
    private def runPlannedNode(
        plan: ExecutionPlan,
        id: TaskId,
        state: AtomicRef[ExecState]
    )(using
        Frame
    ): NodeResult < (Async & Workflow & Abort[WorkflowError]) =
        val task = plan.tasks(id).unsafeTask
        for
            st <- state.get
            depResults = plan.deps(id).map(st.results(_))
            rt <- Workflow.runtime
            observer = rt.observer
            cfg      = rt.config
            _ <- observer.onEvent(WorkflowEvent.TaskQueued(id))
            result <- task match
                case src: Task.Source      => runSource(src, observer)
                case srcs: Task.Sources    => runSources(srcs, observer)
                case in: Task.Input[?]     => runInput(in, observer)
                case c: Task.Cached[?]     => runCached(c, depResults, cfg, observer)
                case p: Task.Persistent[?] => runPersistent(p, depResults, cfg, observer)
                case a: Task.Activity[?]   => runActivity(a, depResults, observer)
                case c: Task.Command[?]    => runCommand(c, depResults, observer)
        yield result
    end runPlannedNode

    // ---------------------------------------------------------------------
    // Source / Input
    // ---------------------------------------------------------------------

    /** Source nodes: re-evaluated each run. Reads the file contents via the runtime VFS and produces a [[VPathRef]]
      * whose fingerprint is either the content hash ([[VPathRef.of]]) or the cheaper size+mtime quick hash
      * ([[VPathRef.quick]]), selected by `src.quick`.
      */
    private def runSource(
        src: Task.Source,
        observer: Observer
    )(using Frame): NodeResult < (Async & Workflow & Abort[WorkflowError]) =
        for
            rt <- Workflow.runtime
            vfs = rt.vfs
            started <- Clock.now
            _       <- observer.onEvent(WorkflowEvent.TaskStarted(src.id, Chunk.empty, started.toJava))
            ctx = newTaskContext(CacheLayout(rt.cacheRoot).destPath(CacheKey.fromTaskId(src.id)))
            ref <- runBody[VPathRef](
                src.id,
                ctx,
                if src.quick then VPathRef.quick(src.path, vfs)
                else VPathRef.of(src.path, vfs)
            )
            _ <- observer.onEvent(WorkflowEvent.TaskCompleted(src.id, ref.fingerprint, 0L))
        yield NodeResult(ref, ref.fingerprint)
    end runSource

    /** Multi-path Source — Mill `Sources` analogue. */
    private def runSources(
        src: Task.Sources,
        observer: Observer
    )(using Frame): NodeResult < (Async & Workflow & Abort[WorkflowError]) =
        for
            rt <- Workflow.runtime
            vfs = rt.vfs
            started <- Clock.now
            _       <- observer.onEvent(WorkflowEvent.TaskStarted(src.id, Chunk.empty, started.toJava))
            ctx = newTaskContext(CacheLayout(rt.cacheRoot).destPath(CacheKey.fromTaskId(src.id)))
            refs <- runBody[Chunk[VPathRef]](
                src.id,
                ctx,
                Kyo.foreach(src.paths)(p =>
                    if src.quick then VPathRef.quick(p, vfs)
                    else VPathRef.of(p, vfs)
                )
            )
            agg = VPathRef.aggregateFingerprint(refs)
            _ <- observer.onEvent(WorkflowEvent.TaskCompleted(src.id, agg, 0L))
        yield NodeResult(refs, agg)
    end runSources

    /** Input nodes: run the `read` thunk and hash the value via the embedded `Hashable`.
      */
    private def runInput(
        in: Task.Input[?],
        observer: Observer
    )(using Frame): NodeResult < (Async & Workflow & Abort[WorkflowError]) =
        for
            rt      <- Workflow.runtime
            started <- Clock.now
            _       <- observer.onEvent(WorkflowEvent.TaskStarted(in.id, Chunk.empty, started.toJava))
            ctx = newTaskContext(CacheLayout(rt.cacheRoot).destPath(CacheKey.fromTaskId(in.id)))
            value <- runBody(in.id, ctx, in.read())
            vh = in.hashable.asInstanceOf[Hashable[Any]].hash(value)
            _ <- observer.onEvent(WorkflowEvent.TaskCompleted(in.id, vh, 0L))
        yield NodeResult(value, vh)
    end runInput

    // ---------------------------------------------------------------------
    // Task.Cached — the main path
    // ---------------------------------------------------------------------

    private def runCached[A](
        task: Task.Cached[A],
        depResults: Chunk[NodeResult],
        cfg: Workflow.Config,
        observer: Observer
    )(using
        Frame
    ): NodeResult < (Async & Workflow & Abort[WorkflowError]) =
        val depArgs  = depResults.toIndexedSeq.map(_.value)
        val depFps   = depResults.map(r => Hashing.DepFingerprint(taskIdOf(task.deps, depResults, r), r.valueHash))
        val bodyHash = Hashing.bodyHash(task.id, task.version)
        val expected = Hashing.inputsHash(task.id, bodyHash, depFps, task.paramHash)
        val key      = CacheKey.fromTaskId(task.id)
        for
            existing <- readManifest(key)
            result <-
                if cfg.noCache || cfg.bypass.contains(task.id) then
                    cacheMiss(task, depArgs, depFps, bodyHash, expected, key, cfg, observer)
                else
                    existing match
                        case Present(existing) =>
                            readCachedRecord(task, existing, bodyHash, expected).flatMap {
                                case Present(record) => cacheHit(task, expected, record, observer)
                                case Absent => cacheMiss(task, depArgs, depFps, bodyHash, expected, key, cfg, observer)
                            }
                        case Absent =>
                            cacheMiss(task, depArgs, depFps, bodyHash, expected, key, cfg, observer)
        yield result
    end runCached

    private def cacheHit[A](
        task: Task.Cached[A],
        expected: Fingerprint,
        record: TaskRecord[A],
        observer: Observer
    )(using Frame): NodeResult < (Async & Workflow & Abort[WorkflowError]) =
        for _ <- observer.onEvent(WorkflowEvent.TaskCached(task.id, expected))
        yield NodeResult(record.value, record.valueHash)

    private def cacheMiss[A](
        task: Task.Cached[A],
        depArgs: IndexedSeq[Any],
        depFps: Chunk[Hashing.DepFingerprint],
        bodyHash: Fingerprint,
        expected: Fingerprint,
        key: CacheKey,
        cfg: Workflow.Config,
        observer: Observer
    )(using Frame): NodeResult < (Async & Workflow & Abort[WorkflowError]) =
        val depIds = depFps.map(_.id)
        for
            rt <- Workflow.runtime
            layout = CacheLayout(rt.cacheRoot)
            started <- Clock.now
            _       <- observer.onEvent(WorkflowEvent.TaskStarted(task.id, depIds, started.toJava))
            result <- Scope.run:
                for
                    dest <- layout.openWorkspace(rt.vfs, key)
                    ctx = newTaskContext(dest)
                    value <- runBody(task.id, ctx, task.value(ctx, depArgs))
                    vh = task.hashable.hash(value)
                    _ <- layout.sealWorkspace(rt.vfs, key)
                yield value -> vh
            value = result._1
            vh    = result._2
            _ <- observer.onEvent(WorkflowEvent.TaskCompleted(task.id, vh, 0L))
            _ <-
                if cfg.readOnly || cfg.noCache then (): Unit < Async
                else writeRecord(key, task, value, bodyHash, expected, vh)
        yield NodeResult(value, vh)

    // ---------------------------------------------------------------------
    // Task.Persistent
    // ---------------------------------------------------------------------

    private def runPersistent[A](
        task: Task.Persistent[A],
        depResults: Chunk[NodeResult],
        cfg: Workflow.Config,
        observer: Observer
    )(using
        Frame
    ): NodeResult < (Async & Workflow & Abort[WorkflowError]) =
        val depArgs  = depResults.toIndexedSeq.map(_.value)
        val depFps   = depResults.map(r => Hashing.DepFingerprint(taskIdOf(task.deps, depResults, r), r.valueHash))
        val bodyHash = Hashing.bodyHash(task.id, task.version)
        val expected = Hashing.inputsHash(task.id, bodyHash, depFps, task.paramHash)
        val key      = CacheKey.fromTaskId(task.id)
        for
            existing <- readManifest(key)
            result <-
                if cfg.noCache || cfg.bypass.contains(task.id) then
                    persistentCacheMiss(task, depArgs, depFps, bodyHash, expected, key, cfg, observer)
                else
                    existing match
                        case Present(existing) =>
                            readPersistentRecord(task, existing, bodyHash, expected).flatMap {
                                case Present(record) => persistentCacheHit(task, expected, record, observer)
                                case Absent =>
                                    persistentCacheMiss(task, depArgs, depFps, bodyHash, expected, key, cfg, observer)
                            }
                        case Absent =>
                            persistentCacheMiss(task, depArgs, depFps, bodyHash, expected, key, cfg, observer)
        yield result
    end runPersistent

    private def persistentCacheHit[A](
        task: Task.Persistent[A],
        expected: Fingerprint,
        record: TaskRecord[A],
        observer: Observer
    )(using Frame): NodeResult < (Async & Workflow & Abort[WorkflowError]) =
        for _ <- observer.onEvent(WorkflowEvent.TaskCached(task.id, expected))
        yield NodeResult(record.value, record.valueHash)

    private def persistentCacheMiss[A](
        task: Task.Persistent[A],
        depArgs: IndexedSeq[Any],
        depFps: Chunk[Hashing.DepFingerprint],
        bodyHash: Fingerprint,
        expected: Fingerprint,
        key: CacheKey,
        cfg: Workflow.Config,
        observer: Observer
    )(using Frame): NodeResult < (Async & Workflow & Abort[WorkflowError]) =
        val depIds = depFps.map(_.id)
        for
            rt <- Workflow.runtime
            layout = CacheLayout(rt.cacheRoot)
            dest    <- layout.openPersistentWorkspace(rt.vfs, key)
            started <- Clock.now
            _       <- observer.onEvent(WorkflowEvent.TaskStarted(task.id, depIds, started.toJava))
            ctx = newTaskContext(dest)
            value <- runBody(task.id, ctx, task.value(ctx, depArgs))
            vh = task.hashable.hash(value)
            _ <- observer.onEvent(WorkflowEvent.TaskCompleted(task.id, vh, 0L))
            _ <-
                if cfg.readOnly || cfg.noCache then (): Unit < Async
                else writeRecord(key, task, value, bodyHash, expected, vh)
        yield NodeResult(value, vh)

    // ---------------------------------------------------------------------
    // Task.Activity
    // ---------------------------------------------------------------------

    private def runActivity[A](
        task: Task.Activity[A],
        depResults: Chunk[NodeResult],
        observer: Observer
    )(using
        Frame
    ): NodeResult < (Async & Workflow & Abort[WorkflowError]) =
        val depArgs = depResults.toIndexedSeq.map(_.value)
        val depIds  = depResults.map(r => taskIdOf(task.deps, depResults, r))
        for
            started <- Clock.now
            _       <- observer.onEvent(WorkflowEvent.TaskStarted(task.id, depIds, started.toJava))
            rt      <- Workflow.runtime
            ctx = newTaskContext(CacheLayout(rt.cacheRoot).destPath(CacheKey.fromTaskId(task.id)))
            value <- runBody(task.id, ctx, task.value(ctx, depArgs))
            vh = task.hashable.hash(value)
            _ <- observer.onEvent(WorkflowEvent.TaskCompleted(task.id, vh, 0L))
        yield NodeResult(value, vh)
    end runActivity

    // ---------------------------------------------------------------------
    // Task.Command
    // ---------------------------------------------------------------------

    private def runCommand(
        task: Task.Command[?],
        depResults: Chunk[NodeResult],
        observer: Observer
    )(using
        Frame
    ): NodeResult < (Async & Workflow & Abort[WorkflowError]) =
        val depArgs = depResults.toIndexedSeq.map(_.value)
        val depIds  = depResults.map(r => taskIdOf(task.deps, depResults, r))
        for
            started <- Clock.now
            _       <- observer.onEvent(WorkflowEvent.TaskStarted(task.id, depIds, started.toJava))
            rt      <- Workflow.runtime
            ctx = newTaskContext(CacheLayout(rt.cacheRoot).destPath(CacheKey.fromTaskId(task.id)))
            value <- runBody(task.id, ctx, task.value(ctx, depArgs))
            vh = Fingerprint.unsafe("command:nostore")
            _ <- observer.onEvent(WorkflowEvent.TaskCompleted(task.id, vh, 0L))
        yield NodeResult(value, vh)
    end runCommand

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private def runBody[A](
        id: TaskId,
        ctx: TaskContext,
        body: => A < Task.BodyEffects
    )(using Frame): A < (Async & Workflow & Abort[WorkflowError]) =
        for
            rt <- Workflow.runtime
            result <- Vfs.run(rt.vfs):
                Abort.run[Throwable | WorkflowError](Workflow.withTaskContext(ctx)(body))
            value <- bodyResult(id, result)
        yield value

    private def bodyResult[A](
        id: TaskId,
        result: Result[Throwable | WorkflowError, A]
    )(using Frame): A < Abort[WorkflowError] =
        result match
            case Result.Success(value) =>
                value
            case Result.Failure(error: WorkflowError) =>
                Abort.fail(error)
            case Result.Failure(error: Throwable) =>
                Abort.fail[WorkflowError](
                    WorkflowError.TaskFailed(id, Option(error.getMessage).getOrElse(error.toString))
                )
            case Result.Panic(error) =>
                Abort.fail[WorkflowError](
                    WorkflowError.TaskFailed(id, Option(error.getMessage).getOrElse(error.toString))
                )

    private def newTaskContext(dest: io.eleven19.kymora.vfs.VPath)(using Frame): TaskContext =
        TaskContext(dest = dest)

    private def taskIdOf(
        deps: Seq[AnyTask],
        results: Chunk[NodeResult],
        target: NodeResult
    ): TaskId =
        val idx = results.indexOf(target)
        deps(idx).id

    private def readManifest(
        key: CacheKey
    )(using Frame): Maybe[StoredManifest] < (Async & Workflow & Abort[WorkflowError]) =
        for
            rt <- Workflow.runtime
            r  <- CacheLayout(rt.cacheRoot).readManifest(rt.vfs, key)
        yield r

    private def readCachedRecord[A](
        task: Task.Cached[A],
        existing: StoredManifest,
        bodyHash: Fingerprint,
        expected: Fingerprint
    )(using Frame): Maybe[TaskRecord[A]] < (Async & Workflow & Abort[WorkflowError]) =
        readTaskRecord(task.cacheable, task.hashable, existing, task.version, bodyHash, expected)

    private def readPersistentRecord[A](
        task: Task.Persistent[A],
        existing: StoredManifest,
        bodyHash: Fingerprint,
        expected: Fingerprint
    )(using Frame): Maybe[TaskRecord[A]] < (Async & Workflow & Abort[WorkflowError]) =
        readTaskRecord(task.cacheable, task.hashable, existing, task.version, bodyHash, expected)

    private def readTaskRecord[A](
        cacheable: Cacheable[A],
        hashable: Hashable[A],
        existing: StoredManifest,
        version: TaskVersion,
        bodyHash: Fingerprint,
        expected: Fingerprint
    )(using Frame): Maybe[TaskRecord[A]] < (Async & Workflow & Abort[WorkflowError]) =
        for
            rt <- Workflow.runtime
            decoded = decodeRecord(existing, cacheable)(using rt.codec)
            result <- validateRecord(decoded, existing.path, version, bodyHash, expected, hashable)
        yield result

    private def decodeRecord[A](
        existing: StoredManifest,
        cacheable: Cacheable[A]
    )(using codec: Codec): Result[WorkflowError, TaskRecord[A]] =
        given Schema[A] = cacheable.schema
        summon[Schema[TaskRecord[A]]].decode(Span.fromUnsafe(existing.bytes.toArray)) match
            case Result.Success(record) =>
                Result.succeed(record)
            case Result.Failure(error) =>
                Result.fail(
                    WorkflowError.Store(
                        StoreError.CorruptManifest(
                            existing.path.show,
                            Option(error.getMessage).getOrElse(error.toString)
                        )
                    )
                )
            case Result.Panic(error) =>
                Result.fail(
                    WorkflowError.Store(
                        StoreError.CorruptManifest(
                            existing.path.show,
                            Option(error.getMessage).getOrElse(error.toString)
                        )
                    )
                )

    private def validateRecord[A](
        decoded: Result[WorkflowError, TaskRecord[A]],
        path: io.eleven19.kymora.vfs.VPath,
        version: TaskVersion,
        bodyHash: Fingerprint,
        expected: Fingerprint,
        hashable: Hashable[A]
    )(using Frame): Maybe[TaskRecord[A]] < Abort[WorkflowError] =
        decoded match
            case Result.Failure(error) =>
                Abort.fail(error)
            case Result.Panic(error) =>
                Abort.fail(
                    WorkflowError.Store(
                        StoreError.CorruptManifest(path.show, Option(error.getMessage).getOrElse(error.toString))
                    )
                )
            case Result.Success(record) =>
                if record.schemaVersion != TaskRecord.CurrentSchemaVersion then
                    Abort.fail(
                        WorkflowError.Store(
                            StoreError.UnknownSchemaVersion(record.schemaVersion, TaskRecord.CurrentSchemaVersion)
                        )
                    )
                else if record.bodyVersion != version || record.bodyHash != bodyHash || record.inputsHash != expected
                then Maybe.empty[TaskRecord[A]]
                else if hashable.hash(record.value) != record.valueHash then
                    Abort.fail(
                        WorkflowError.Store(
                            StoreError.CorruptManifest(path.show, "cached value hash does not match manifest")
                        )
                    )
                else Maybe(record)

    private def writeRecord[A](
        key: CacheKey,
        task: Task.Cached[A] | Task.Persistent[A],
        value: A,
        bodyHash: Fingerprint,
        inputsHash: Fingerprint,
        valueHash: Fingerprint
    )(using Frame): Unit < (Async & Workflow & Abort[WorkflowError]) =
        for
            rt <- Workflow.runtime
            record = TaskRecord[A](
                schemaVersion = TaskRecord.CurrentSchemaVersion,
                value = value,
                valueHash = valueHash,
                inputsHash = inputsHash,
                bodyVersion = task.version,
                bodyHash = bodyHash,
                workflowVersion = "dev",
                scalaVersion = "unknown",
                runtime = io.eleven19.kymora.workflow.store.Runtime("unknown", "unknown", Maybe.empty),
                createdAt = java.time.Instant.now
            )
            bytes = encodeRecord(record, task)(using rt.codec)
            _ <- CacheLayout(rt.cacheRoot).writeManifest(rt.vfs, key, bytes)
        yield ()

    private def encodeRecord[A](
        record: TaskRecord[A],
        task: Task.Cached[A] | Task.Persistent[A]
    )(using codec: Codec): Chunk[Byte] =
        given Schema[A] = task match
            case cached: Task.Cached[A]         => cached.cacheable.schema
            case persistent: Task.Persistent[A] => persistent.cacheable.schema
        Chunk.from(summon[Schema[TaskRecord[A]]].encode(record).toArray)

end Scheduler
