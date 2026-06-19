# Workflow Telemetry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current direct observer callback path with a first-class workflow telemetry layer that supports static task-plan inspection, structured live event fanout, state snapshots, and UI-friendly state streaming while preserving existing observer behavior.

**Architecture:** Keep `WorkflowEvent` as the canonical event protocol. Extract a public plan model from the existing scheduler planning phase, introduce `WorkflowTelemetry` as the runtime event/state substrate, implement it with Kyo `Hub` for event fanout and a Kyo `Actor` + `SignalRef` state projector for deterministic run-state updates, and adapt existing `Observer`s to the new layer.

**Tech Stack:** Scala 3.8.4, Mill, Kyo `Hub`, `Actor`, `Signal`, `Stream`, existing `Workflow`, `Task`, `Observer`, `WorkflowEvent`, and kyo-test.

---

## File Structure

- Create `kymora/workflow/src/io/eleven19/kymora/workflow/WorkflowPlan.scala`
  - Public static inspection model: task kind, direct deps, goals, stable order.
- Create `kymora/workflow/src/io/eleven19/kymora/workflow/WorkflowRunState.scala`
  - Public live-state model projected from `WorkflowEvent`s.
- Create `kymora/workflow/src/io/eleven19/kymora/workflow/WorkflowTelemetry.scala`
  - Runtime telemetry interface and constructors.
- Create `kymora/workflow/src/io/eleven19/kymora/workflow/internal/Planner.scala`
  - Shared graph planning logic used by `Workflow.inspect` and `Scheduler`.
- Create `kymora/workflow/src/io/eleven19/kymora/workflow/internal/HubWorkflowTelemetry.scala`
  - Kyo `Hub` + `Actor` + `SignalRef` implementation.
- Modify `kymora/workflow/src/io/eleven19/kymora/workflow/Workflow.scala`
  - Add `Runtime.telemetry`, compatibility wiring for `Runtime.observer`, and public `inspect`.
- Modify `kymora/workflow/src/io/eleven19/kymora/workflow/internal/Scheduler.scala`
  - Use shared `Planner` and publish through `WorkflowTelemetry`.
- Modify `kymora/workflow/src/io/eleven19/kymora/workflow/Observer.scala`
  - Add observer-to-telemetry adapters while keeping existing APIs source-compatible.
- Modify `kymora/workflow/src/io/eleven19/kymora/workflow/ConsoleObserver.scala`
  - No behavior change; keep as an observer adapter target.
- Modify `kymora/workflow/src/io/eleven19/kymora/workflow/JsonLinesObserver.scala`
  - No behavior change; keep as an observer adapter target.
- Modify `kymora/workflow/package.mill`
  - Add `kyo-actor` dependency for all workflow platforms.
- Create tests:
  - `kymora/workflow/test/src/io/eleven19/kymora/workflow/WorkflowPlanTests.scala`
  - `kymora/workflow/test/src/io/eleven19/kymora/workflow/WorkflowRunStateTests.scala`
  - `kymora/workflow/test/src/io/eleven19/kymora/workflow/WorkflowTelemetryTests.scala`
  - `kymora/workflow/test/src/io/eleven19/kymora/workflow/ObserverCompatibilityTests.scala`
- Modify docs:
  - `kymora/workflow/README.md`

## Constraints And Decisions

- Preserve `Workflow.Runtime(..., observer = Observer.NoOp, codec = Json())` source compatibility.
- Keep `WorkflowEvent` as the durable event protocol.
- Do not expose internal `AnyTask` or executable task bodies through `WorkflowPlan`.
- Public inspect output must be pure metadata: ids, kind, deps, goals, order.
- Event publishing must not require a live subscriber.
- Live UI consumers should subscribe to state snapshots or state changes, not reconstruct state from events unless they explicitly want an event log.
- The default runtime must remain silent and low overhead.
- For backpressure, use bounded `Hub` buffers for exact event delivery. Add dropping/sliding UI adapters later if live dashboards need lossy rendering under heavy event rates.

---

### Task 1: Add Kyo Actor Dependency

**Files:**
- Modify: `kymora/workflow/package.mill`

- [ ] **Step 1: Add `kyo-actor` to workflow dependencies**

Edit `WorkflowModule.mvnDeps`:

```scala
def mvnDeps = Seq(
    mvn"io.getkyo::kyo-core::${_root_.build.KymoraVersions.Kyo}",
    mvn"io.getkyo::kyo-schema::${_root_.build.KymoraVersions.Kyo}",
    mvn"io.getkyo::kyo-case-app::${_root_.build.KymoraVersions.Kyo}",
    mvn"io.getkyo::kyo-actor::${_root_.build.KymoraVersions.Kyo}",
    // Pure-Scala BLAKE3 — cross-platform (JVM / Scala.js / Wasm / Scala Native)
    // via `%%%`. Used by the engine's `Fingerprint` primitive so cache
    // manifests are byte-identical across platforms.
    mvn"pt.kcry::blake3::3.1.2"
)
```

Edit the `wasm` override:

```scala
override def mvnDeps = Seq(
    mvn"io.getkyo::kyo-core::${_root_.build.KymoraVersions.Kyo}",
    mvn"io.getkyo::kyo-schema::${_root_.build.KymoraVersions.Kyo}",
    mvn"io.getkyo::kyo-case-app::${_root_.build.KymoraVersions.Kyo}",
    mvn"io.getkyo::kyo-actor::${_root_.build.KymoraVersions.Kyo}",
    mvn"pt.kcry:blake3_sjs1_3:3.1.2"
)
```

- [ ] **Step 2: Verify dependency resolves**

Run:

```sh
./mill --no-server kymora.workflow.jvm.compile
```

Expected: compile succeeds or fails only because `kyo-actor` artifact naming is unsupported on a platform. If artifact resolution fails, inspect Kyo module coordinates in `.ref/getkyo/kyo/kyo-actor/package.mill` and correct the Mill dependency spelling before moving on.

- [ ] **Step 3: Checkpoint**

Run:

```sh
jj describe -m "Add workflow actor dependency"
```

---

### Task 2: Public Static Plan Model

**Files:**
- Create: `kymora/workflow/src/io/eleven19/kymora/workflow/WorkflowPlan.scala`
- Create: `kymora/workflow/test/src/io/eleven19/kymora/workflow/WorkflowPlanTests.scala`

- [ ] **Step 1: Write failing tests for plan shape**

Create `WorkflowPlanTests.scala`:

```scala
package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class WorkflowPlanTests extends Test[Any]:

  "Workflow.inspect exposes goals, dependencies, and stable execution order" in {
    val source = Task.source("source")(io.eleven19.kymora.vfs.VPath("input.txt"))
    val parse  = Task.cached("parse")(source)(ref => ref.path.show)
    val check  = Task.cached("check")(parse)(text => text.length)
    val pack   = Task.command("pack")(check)(n => n)

    val plan = Workflow.inspect(pack)

    assert(plan.goalIds == Chunk(TaskId("pack")))
    assert(plan.order == Chunk(TaskId("source"), TaskId("parse"), TaskId("check"), TaskId("pack")))
    assert(plan.node(TaskId("source")).map(_.kind) == Maybe(WorkflowPlan.TaskKind.Source))
    assert(plan.node(TaskId("parse")).map(_.deps) == Maybe(Chunk(TaskId("source"))))
    assert(plan.node(TaskId("check")).map(_.deps) == Maybe(Chunk(TaskId("parse"))))
    assert(plan.node(TaskId("pack")).map(_.kind) == Maybe(WorkflowPlan.TaskKind.Command))
  }

  "Workflow.inspect reports duplicate task ids as WorkflowError" in {
    val left  = Task.cached("dup")(1)
    val right = Task.cached("dup")(2)
    val root  = Task.cached("root")(left, right)((_, _) => 0)

    val result = Workflow.inspectResult(root)

    assert(result.isFailure)
  }

end WorkflowPlanTests
```

- [ ] **Step 2: Run tests and verify they fail for missing API**

Run:

```sh
./mill --no-server kymora.workflow.jvm.test -k WorkflowPlanTests
```

Expected: compile failure mentioning missing `Workflow.inspect`, `Workflow.inspectResult`, or `WorkflowPlan`.

- [ ] **Step 3: Add public plan model**

Create `WorkflowPlan.scala`:

```scala
package io.eleven19.kymora.workflow

import kyo.*

/** Static, execution-free description of the reachable task graph for one or more workflow goals. */
final case class WorkflowPlan(
    nodes: Map[TaskId, WorkflowPlan.Node],
    goalIds: Chunk[TaskId],
    order: Chunk[TaskId]
) derives CanEqual:

    def node(id: TaskId): Maybe[WorkflowPlan.Node] =
        nodes.get(id).fold(Maybe.empty[WorkflowPlan.Node])(Maybe(_))

end WorkflowPlan

object WorkflowPlan:

    final case class Node(
        id: TaskId,
        kind: TaskKind,
        deps: Chunk[TaskId],
        version: TaskVersion
    ) derives CanEqual

    enum TaskKind derives CanEqual:
        case Source
        case Sources
        case Input
        case Cached
        case Persistent
        case Activity
        case Command

end WorkflowPlan
```

- [ ] **Step 4: Run tests and verify remaining failure**

Run:

```sh
./mill --no-server kymora.workflow.jvm.test -k WorkflowPlanTests
```

Expected: compile failure now limited to missing `Workflow.inspect` and `Workflow.inspectResult`.

- [ ] **Step 5: Checkpoint**

Run:

```sh
jj describe -m "Add workflow plan model"
```

---

### Task 3: Extract Shared Planner

**Files:**
- Create: `kymora/workflow/src/io/eleven19/kymora/workflow/internal/Planner.scala`
- Modify: `kymora/workflow/src/io/eleven19/kymora/workflow/internal/Scheduler.scala`
- Modify: `kymora/workflow/src/io/eleven19/kymora/workflow/Workflow.scala`
- Test: `kymora/workflow/test/src/io/eleven19/kymora/workflow/WorkflowPlanTests.scala`

- [ ] **Step 1: Create internal planner**

Create `Planner.scala`:

```scala
package io.eleven19.kymora.workflow.internal

import io.eleven19.kymora.workflow.*
import kyo.*

private[workflow] object Planner:

    final case class ExecutionPlan(
        tasks: Map[TaskId, AnyTask],
        deps: Map[TaskId, Chunk[TaskId]],
        goalIds: Chunk[TaskId],
        order: Chunk[TaskId]
    )

    def build(goals: Chunk[Task[?]]): Result[WorkflowError, ExecutionPlan] =
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

    def inspect(goals: Chunk[Task[?]]): Result[WorkflowError, WorkflowPlan] =
        build(goals).map { plan =>
            val nodes =
                plan.tasks.map { (id, task) =>
                    id -> WorkflowPlan.Node(
                        id = id,
                        kind = kindOf(task),
                        deps = plan.deps(id),
                        version = task.unsafeTask.version
                    )
                }
            WorkflowPlan(nodes = nodes, goalIds = plan.goalIds, order = plan.order)
        }

    private def stableOrder(goals: Chunk[AnyTask]): Chunk[TaskId] =
        val visited = scala.collection.mutable.Set.empty[TaskId]
        val result  = Chunk.newBuilder[TaskId]

        def visit(task: AnyTask): Unit =
            if visited.contains(task.id) then ()
            else
                Graph.depsOf(task).foreach(visit)
                val _ = visited.add(task.id)
                result.addOne(task.id)

        goals.foreach(visit)
        result.result()

    private def kindOf(task: AnyTask): WorkflowPlan.TaskKind =
        task.unsafeTask match
            case _: Task.Source        => WorkflowPlan.TaskKind.Source
            case _: Task.Sources       => WorkflowPlan.TaskKind.Sources
            case _: Task.Input[?]      => WorkflowPlan.TaskKind.Input
            case _: Task.Cached[?]     => WorkflowPlan.TaskKind.Cached
            case _: Task.Persistent[?] => WorkflowPlan.TaskKind.Persistent
            case _: Task.Activity[?]   => WorkflowPlan.TaskKind.Activity
            case _: Task.Command[?]    => WorkflowPlan.TaskKind.Command

end Planner
```

- [ ] **Step 2: Replace private scheduler plan type**

In `Scheduler.scala`, delete the private `ExecutionPlan` case class, private `plan` method, and private `stableOrder` method.

Add aliases at the top of `Scheduler`:

```scala
private type ExecutionPlan = Planner.ExecutionPlan
```

In `executeAll`, replace:

```scala
plan <- Abort.get(plan(Chunk.from(goals)))
```

with:

```scala
plan <- Abort.get(Planner.build(Chunk.from(goals)))
```

- [ ] **Step 3: Add public inspect API**

In `Workflow.scala`, add imports:

```scala
import io.eleven19.kymora.workflow.internal.Planner
```

Add methods in `object Workflow`:

```scala
def inspectResult(goals: Task[?]*): Result[WorkflowError, WorkflowPlan] =
    Planner.inspect(Chunk.from(goals))

def inspect(goals: Task[?]*): WorkflowPlan =
    inspectResult(goals*) match
        case Result.Success(plan) => plan
        case Result.Failure(error) =>
            throw WorkflowException(error)
        case Result.Panic(error) =>
            throw error
```

- [ ] **Step 4: Run plan tests**

Run:

```sh
./mill --no-server kymora.workflow.jvm.test -k WorkflowPlanTests
```

Expected: `WorkflowPlanTests` passes.

- [ ] **Step 5: Run scheduler regression tests**

Run:

```sh
./mill --no-server kymora.workflow.jvm.test -k EngineTests
```

Expected: `EngineTests` passes, proving the extracted planner still feeds execution.

- [ ] **Step 6: Checkpoint**

Run:

```sh
jj describe -m "Expose workflow inspection plan"
```

---

### Task 4: Run State Model

**Files:**
- Create: `kymora/workflow/src/io/eleven19/kymora/workflow/WorkflowRunState.scala`
- Create: `kymora/workflow/test/src/io/eleven19/kymora/workflow/WorkflowRunStateTests.scala`

- [ ] **Step 1: Write failing state-fold tests**

Create `WorkflowRunStateTests.scala`:

```scala
package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class WorkflowRunStateTests extends Test[Any]:

  "WorkflowRunState applies run and task lifecycle events" in {
    val started = java.time.Instant.parse("2026-06-19T20:00:00Z")
    val state =
      WorkflowRunState.empty
        .applyEvent(WorkflowEvent.RunStarted(Chunk(TaskId("compile")), started))
        .applyEvent(WorkflowEvent.TaskQueued(TaskId("compile")))
        .applyEvent(WorkflowEvent.TaskStarted(TaskId("compile"), Chunk(TaskId("source")), started))
        .applyEvent(WorkflowEvent.TaskCompleted(TaskId("compile"), Fingerprint.unsafe("blake3:value"), 42L))
        .applyEvent(WorkflowEvent.RunCompleted(durationMs = 50L, hits = 0, misses = 1, failed = 0))

    assert(state.goals == Chunk(TaskId("compile")))
    assert(state.tasks(TaskId("compile")).status == WorkflowRunState.TaskStatus.Succeeded)
    assert(state.tasks(TaskId("compile")).deps == Chunk(TaskId("source")))
    assert(state.completed)
    assert(state.hits == 0)
    assert(state.misses == 1)
    assert(state.failed == 0)
  }

  "WorkflowRunState records cache hits, failures, and cancellations" in {
    val state =
      WorkflowRunState.empty
        .applyEvent(WorkflowEvent.TaskQueued(TaskId("cached")))
        .applyEvent(WorkflowEvent.TaskCached(TaskId("cached"), Fingerprint.unsafe("blake3:inputs")))
        .applyEvent(WorkflowEvent.TaskFailed(TaskId("failed"), "boom"))
        .applyEvent(WorkflowEvent.TaskCancelled(TaskId("blocked"), "dependency failed"))

    assert(state.tasks(TaskId("cached")).status == WorkflowRunState.TaskStatus.Cached)
    assert(state.tasks(TaskId("failed")).message == Maybe("boom"))
    assert(state.tasks(TaskId("blocked")).status == WorkflowRunState.TaskStatus.Cancelled)
  }

end WorkflowRunStateTests
```

- [ ] **Step 2: Run tests and verify missing type failure**

Run:

```sh
./mill --no-server kymora.workflow.jvm.test -k WorkflowRunStateTests
```

Expected: compile failure mentioning missing `WorkflowRunState`.

- [ ] **Step 3: Add run state model**

Create `WorkflowRunState.scala`:

```scala
package io.eleven19.kymora.workflow

import kyo.*

final case class WorkflowRunState(
    goals: Chunk[TaskId],
    tasks: Map[TaskId, WorkflowRunState.Task],
    completed: Boolean,
    durationMs: Maybe[Long],
    hits: Int,
    misses: Int,
    failed: Int,
    events: Chunk[WorkflowEvent]
) derives CanEqual:

    def applyEvent(event: WorkflowEvent): WorkflowRunState =
        val next = event match
            case WorkflowEvent.RunStarted(goalIds, _) =>
                copy(goals = goalIds)
            case WorkflowEvent.RunCompleted(duration, h, m, f) =>
                copy(completed = true, durationMs = Maybe(duration), hits = h, misses = m, failed = f)
            case WorkflowEvent.TaskQueued(id) =>
                updateTask(id)(_.copy(status = WorkflowRunState.TaskStatus.Queued))
            case WorkflowEvent.TaskStarted(id, deps, at) =>
                updateTask(id)(_.copy(status = WorkflowRunState.TaskStatus.Running, deps = deps, startedAt = Maybe(at)))
            case WorkflowEvent.TaskCached(id, inputsHash) =>
                updateTask(id)(_.copy(status = WorkflowRunState.TaskStatus.Cached, inputsHash = Maybe(inputsHash)))
            case WorkflowEvent.TaskCompleted(id, valueHash, duration) =>
                updateTask(id)(_.copy(status = WorkflowRunState.TaskStatus.Succeeded, valueHash = Maybe(valueHash), durationMs = Maybe(duration)))
            case WorkflowEvent.TaskFailed(id, message) =>
                updateTask(id)(_.copy(status = WorkflowRunState.TaskStatus.Failed, message = Maybe(message)))
            case WorkflowEvent.TaskCancelled(id, reason) =>
                updateTask(id)(_.copy(status = WorkflowRunState.TaskStatus.Cancelled, message = Maybe(reason)))

        next.copy(events = next.events.append(event))

    private def updateTask(id: TaskId)(f: WorkflowRunState.Task => WorkflowRunState.Task): WorkflowRunState =
        val current = tasks.getOrElse(id, WorkflowRunState.Task(id))
        copy(tasks = tasks.updated(id, f(current)))

end WorkflowRunState

object WorkflowRunState:

    def empty: WorkflowRunState =
        WorkflowRunState(
            goals = Chunk.empty,
            tasks = Map.empty,
            completed = false,
            durationMs = Maybe.empty,
            hits = 0,
            misses = 0,
            failed = 0,
            events = Chunk.empty
        )

    final case class Task(
        id: TaskId,
        status: TaskStatus = TaskStatus.Pending,
        deps: Chunk[TaskId] = Chunk.empty,
        startedAt: Maybe[java.time.Instant] = Maybe.empty,
        inputsHash: Maybe[Fingerprint] = Maybe.empty,
        valueHash: Maybe[Fingerprint] = Maybe.empty,
        durationMs: Maybe[Long] = Maybe.empty,
        message: Maybe[String] = Maybe.empty
    ) derives CanEqual

    enum TaskStatus derives CanEqual:
        case Pending
        case Queued
        case Running
        case Cached
        case Succeeded
        case Failed
        case Cancelled

end WorkflowRunState
```

- [ ] **Step 4: Run state tests**

Run:

```sh
./mill --no-server kymora.workflow.jvm.test -k WorkflowRunStateTests
```

Expected: `WorkflowRunStateTests` passes.

- [ ] **Step 5: Checkpoint**

Run:

```sh
jj describe -m "Add workflow run state model"
```

---

### Task 5: Telemetry Interface

**Files:**
- Create: `kymora/workflow/src/io/eleven19/kymora/workflow/WorkflowTelemetry.scala`
- Modify: `kymora/workflow/src/io/eleven19/kymora/workflow/Observer.scala`
- Create: `kymora/workflow/test/src/io/eleven19/kymora/workflow/ObserverCompatibilityTests.scala`

- [ ] **Step 1: Write failing observer compatibility test**

Create `ObserverCompatibilityTests.scala`:

```scala
package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class ObserverCompatibilityTests extends Test[Any]:

  "Observer.asTelemetry forwards published events to the observer" in {
    for
      seen <- AtomicRef.init(Chunk.empty[WorkflowEvent])
      observer = new Observer:
        override def onEvent(event: WorkflowEvent): Unit < Async =
          seen.update(_.append(event)).unit
      telemetry = observer.asTelemetry
      _ <- telemetry.publish(WorkflowEvent.TaskQueued(TaskId("compile")))
      events <- seen.get
    yield assert(events == Chunk(WorkflowEvent.TaskQueued(TaskId("compile"))))
  }

end ObserverCompatibilityTests
```

- [ ] **Step 2: Run test and verify missing API failure**

Run:

```sh
./mill --no-server kymora.workflow.jvm.test -k ObserverCompatibilityTests
```

Expected: compile failure mentioning missing `WorkflowTelemetry` or `asTelemetry`.

- [ ] **Step 3: Add telemetry interface**

Create `WorkflowTelemetry.scala`:

```scala
package io.eleven19.kymora.workflow

import kyo.*

trait WorkflowTelemetry:

    def publish(event: WorkflowEvent)(using Frame): Unit < Async

    def snapshot(using Frame): WorkflowRunState < Async =
        WorkflowRunState.empty

end WorkflowTelemetry

object WorkflowTelemetry:

    object NoOp extends WorkflowTelemetry:
        override def publish(event: WorkflowEvent)(using Frame): Unit < Async = ()

    def fromObserver(observer: Observer): WorkflowTelemetry =
        new WorkflowTelemetry:
            override def publish(event: WorkflowEvent)(using Frame): Unit < Async =
                observer.onEvent(event)

end WorkflowTelemetry
```

- [ ] **Step 4: Add observer adapter**

In `Observer.scala`, add this extension inside `object Observer`:

```scala
extension (observer: Observer)
    def asTelemetry: WorkflowTelemetry =
        WorkflowTelemetry.fromObserver(observer)
```

- [ ] **Step 5: Run observer compatibility test**

Run:

```sh
./mill --no-server kymora.workflow.jvm.test -k ObserverCompatibilityTests
```

Expected: test passes.

- [ ] **Step 6: Checkpoint**

Run:

```sh
jj describe -m "Add workflow telemetry interface"
```

---

### Task 6: Hub + Actor Telemetry Implementation

**Files:**
- Create: `kymora/workflow/src/io/eleven19/kymora/workflow/internal/HubWorkflowTelemetry.scala`
- Modify: `kymora/workflow/src/io/eleven19/kymora/workflow/WorkflowTelemetry.scala`
- Create: `kymora/workflow/test/src/io/eleven19/kymora/workflow/WorkflowTelemetryTests.scala`

- [ ] **Step 1: Write failing event fanout and state tests**

Create `WorkflowTelemetryTests.scala`:

```scala
package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class WorkflowTelemetryTests extends Test[Any]:

  "WorkflowTelemetry.live fans out events to multiple listeners" in {
    Scope.run {
      for
        telemetry <- WorkflowTelemetry.live()
        left      <- telemetry.listen()
        right     <- telemetry.listen()
        event      = WorkflowEvent.TaskQueued(TaskId("compile"))
        _         <- telemetry.publish(event)
        l         <- left.take
        r         <- right.take
      yield
        assert(l == event)
        assert(r == event)
    }
  }

  "WorkflowTelemetry.live projects published events into snapshot state" in {
    Scope.run {
      for
        telemetry <- WorkflowTelemetry.live()
        _ <- telemetry.publish(WorkflowEvent.TaskQueued(TaskId("compile")))
        _ <- telemetry.publish(WorkflowEvent.TaskStarted(TaskId("compile"), Chunk.empty, java.time.Instant.parse("2026-06-19T20:00:00Z")))
        _ <- telemetry.publish(WorkflowEvent.TaskCompleted(TaskId("compile"), Fingerprint.unsafe("blake3:value"), 12L))
        state <- telemetry.snapshot
      yield assert(state.tasks(TaskId("compile")).status == WorkflowRunState.TaskStatus.Succeeded)
    }
  }

end WorkflowTelemetryTests
```

- [ ] **Step 2: Run tests and verify missing live/listen failure**

Run:

```sh
./mill --no-server kymora.workflow.jvm.test -k WorkflowTelemetryTests
```

Expected: compile failure mentioning missing `WorkflowTelemetry.live` or `listen`.

- [ ] **Step 3: Extend telemetry interface for streams and state**

Update `WorkflowTelemetry.scala`:

```scala
package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.internal.HubWorkflowTelemetry
import kyo.*

trait WorkflowTelemetry:

    def publish(event: WorkflowEvent)(using Frame): Unit < Async

    def snapshot(using Frame): WorkflowRunState < Async =
        WorkflowRunState.empty

    def listen(bufferSize: Int = WorkflowTelemetry.DefaultBufferSize)(
        using Frame
    ): Hub.Listener[WorkflowEvent] < (Sync & Scope & Abort[Closed]) =
        Abort.fail(Closed("WorkflowTelemetry.listen", Frame.internal))

end WorkflowTelemetry

object WorkflowTelemetry:

    inline val DefaultBufferSize: Int = 4096

    object NoOp extends WorkflowTelemetry:
        override def publish(event: WorkflowEvent)(using Frame): Unit < Async = ()

    def fromObserver(observer: Observer): WorkflowTelemetry =
        new WorkflowTelemetry:
            override def publish(event: WorkflowEvent)(using Frame): Unit < Async =
                observer.onEvent(event)

    def live(bufferSize: Int = DefaultBufferSize)(using Frame): WorkflowTelemetry < (Sync & Scope & Async) =
        HubWorkflowTelemetry.init(bufferSize)

end WorkflowTelemetry
```

- [ ] **Step 4: Implement Hub telemetry with actor projector**

Create `HubWorkflowTelemetry.scala`:

```scala
package io.eleven19.kymora.workflow.internal

import io.eleven19.kymora.workflow.*
import kyo.*

private[workflow] final class HubWorkflowTelemetry private (
    hub: Hub[WorkflowEvent],
    stateRef: SignalRef[WorkflowRunState],
    projector: Actor[Nothing, HubWorkflowTelemetry.ProjectorMessage, Unit]
) extends WorkflowTelemetry:

    override def publish(event: WorkflowEvent)(using Frame): Unit < Async =
        Abort.recover[Closed](_ => ()):
            hub.put(event).andThen(projector.send(HubWorkflowTelemetry.ProjectorMessage.Apply(event)))

    override def snapshot(using Frame): WorkflowRunState < Async =
        stateRef.current

    override def listen(bufferSize: Int)(using Frame): Hub.Listener[WorkflowEvent] < (Sync & Scope & Abort[Closed]) =
        hub.listen(bufferSize)

end HubWorkflowTelemetry

private[workflow] object HubWorkflowTelemetry:

    enum ProjectorMessage derives CanEqual:
        case Apply(event: WorkflowEvent)

    def init(bufferSize: Int)(using Frame): HubWorkflowTelemetry < (Sync & Scope & Async) =
        for
            hub      <- Hub.init[WorkflowEvent](bufferSize)
            stateRef <- Signal.initRef(WorkflowRunState.empty)
            projector <- Actor.run[Nothing, ProjectorMessage, Unit, Any] {
                Actor.receiveAll[ProjectorMessage] {
                    case ProjectorMessage.Apply(event) =>
                        stateRef.update(_.applyEvent(event)).unit
                }
            }
        yield HubWorkflowTelemetry(hub, stateRef, projector)

end HubWorkflowTelemetry
```

- [ ] **Step 5: Run telemetry tests**

Run:

```sh
./mill --no-server kymora.workflow.jvm.test -k WorkflowTelemetryTests
```

Expected: `WorkflowTelemetryTests` passes. If `stateRef.current` is not the correct Kyo SignalRef accessor in the pinned Kyo version, inspect `.ref/getkyo/kyo/kyo-core/shared/src/main/scala/kyo/Signal.scala` and use the available current-value method while keeping the public `snapshot` signature unchanged.

- [ ] **Step 6: Checkpoint**

Run:

```sh
jj describe -m "Add hub workflow telemetry"
```

---

### Task 7: Runtime Wiring And Scheduler Publishing

**Files:**
- Modify: `kymora/workflow/src/io/eleven19/kymora/workflow/Workflow.scala`
- Modify: `kymora/workflow/src/io/eleven19/kymora/workflow/internal/Scheduler.scala`
- Modify tests if constructor shape changes:
  - `kymora/workflow/test/src/io/eleven19/kymora/workflow/ConfigTests.scala`
  - `kymora/workflow-testkit/src/io/eleven19/kymora/workflow/testkit/WorkflowTestDriver.scala`

- [ ] **Step 1: Add runtime compatibility test**

Extend `ConfigTests.scala` with:

```scala
"Runtime observer is exposed as telemetry for compatibility" in {
  val runtime = Workflow.Runtime(Vfs.inMemory.named("runtime-telemetry"))
  assert(runtime.observer eq Observer.NoOp)
  assert(runtime.telemetry == Observer.NoOp.asTelemetry)
}
```

If strict equality rejects `WorkflowTelemetry` equality, use this behavior assertion instead:

```scala
"Runtime observer is exposed as telemetry for compatibility" in {
  for
    seen <- AtomicRef.init(0)
    observer = new Observer:
      override def onEvent(event: WorkflowEvent): Unit < Async =
        seen.update(_ + 1).unit
    runtime = Workflow.Runtime(vfs = Vfs.inMemory.named("runtime-telemetry"), observer = observer)
    _      <- runtime.telemetry.publish(WorkflowEvent.TaskQueued(TaskId("x")))
    count  <- seen.get
  yield assert(count == 1)
}
```

- [ ] **Step 2: Run test and verify failure**

Run:

```sh
./mill --no-server kymora.workflow.jvm.test -k ConfigTests
```

Expected: compile failure mentioning missing `Runtime.telemetry`.

- [ ] **Step 3: Add telemetry to runtime**

Modify `Workflow.Runtime` in `Workflow.scala`:

```scala
final case class Runtime(
    config: Workflow.Config = Workflow.Config.default,
    vfs: Vfs.Backend,
    cacheRoot: VPath = VPath("cache"),
    observer: Observer = Observer.NoOp,
    codec: Codec = Json(),
    telemetryOverride: Maybe[WorkflowTelemetry] = Maybe.empty
):
    def telemetry: WorkflowTelemetry =
        telemetryOverride.getOrElse(observer.asTelemetry)
```

Update existing `Runtime.apply(vfs)` and `Runtime.default` constructors to rely on default `telemetryOverride = Maybe.empty`.

- [ ] **Step 4: Replace scheduler observer calls with telemetry publish**

In `Scheduler.scala`, keep method parameters named `observer` for the smallest diff in this task, but pass `rt.telemetry` from runtime:

```scala
val telemetry = rt.telemetry
```

Replace event calls mechanically:

```scala
observer.onEvent(event)
```

with:

```scala
observer.publish(event)
```

Then rename parameters from `observer: Observer` to `telemetry: WorkflowTelemetry` in changed helper methods. Keep this change mechanical:

```scala
private def emitFailure(
    id: TaskId,
    error: WorkflowError,
    telemetry: WorkflowTelemetry
)(using Frame): Unit < Async =
    error match
        case WorkflowError.TaskCancelled(_, reason) =>
            telemetry.publish(WorkflowEvent.TaskCancelled(id, reason))
        case WorkflowError.TaskFailed(_, message) =>
            telemetry.publish(WorkflowEvent.TaskFailed(id, message))
        case other =>
            telemetry.publish(WorkflowEvent.TaskFailed(id, other.toString))
```

- [ ] **Step 5: Run compatibility and engine tests**

Run:

```sh
./mill --no-server kymora.workflow.jvm.test -k ConfigTests
./mill --no-server kymora.workflow.jvm.test -k EngineTests
./mill --no-server kymora.workflow.jvm.test -k ParallelismTests
```

Expected: all selected tests pass.

- [ ] **Step 6: Checkpoint**

Run:

```sh
jj describe -m "Route scheduler events through telemetry"
```

---

### Task 8: Testkit Live Telemetry Support

**Files:**
- Modify: `kymora/workflow-testkit/src/io/eleven19/kymora/workflow/testkit/WorkflowTestDriver.scala`
- Modify or create: `kymora/workflow-testkit/test/src/io/eleven19/kymora/workflow/testkit/WorkflowTestDriverTests.scala`

- [ ] **Step 1: Write failing testkit snapshot test**

Add to `WorkflowTestDriverTests.scala`:

```scala
"WorkflowTestDriver exposes live telemetry state" in {
  val goal = Task.cached("driver-telemetry")(42)
  for
    driver <- WorkflowTestDriver.init
    _      <- driver.run(goal)
    state  <- driver.telemetry.snapshot
  yield assert(state.tasks(TaskId("driver-telemetry")).status == WorkflowRunState.TaskStatus.Succeeded)
}
```

- [ ] **Step 2: Run test and verify failure**

Run:

```sh
./mill --no-server kymora.workflow-testkit.jvm.test -k WorkflowTestDriverTests
```

Expected: compile failure mentioning missing `driver.telemetry`.

- [ ] **Step 3: Update WorkflowTestDriver to use live telemetry**

Modify constructor:

```scala
final class WorkflowTestDriver private (
    val config: Workflow.Config,
    val vfs: Vfs.Backend,
    val store: CacheStore,
    val observer: CollectingObserver,
    val telemetry: WorkflowTelemetry
):
```

Modify runtime:

```scala
def runtime: Workflow.Runtime =
  Workflow.Runtime(config, vfs, VPath("cache"), observer, Json(), Maybe(telemetry))
```

Modify `init`:

```scala
telemetry <- WorkflowTelemetry.live()
```

and construct:

```scala
yield new WorkflowTestDriver(cfg, vfs, store, observer, telemetry)
```

Keep `events` backed by `CollectingObserver` for source compatibility.

- [ ] **Step 4: Run testkit tests**

Run:

```sh
./mill --no-server kymora.workflow-testkit.jvm.test
```

Expected: all workflow-testkit JVM tests pass.

- [ ] **Step 5: Checkpoint**

Run:

```sh
jj describe -m "Expose live telemetry in workflow testkit"
```

---

### Task 9: Live UI And Reporter API Surface

**Files:**
- Modify: `kymora/workflow/src/io/eleven19/kymora/workflow/WorkflowTelemetry.scala`
- Create or extend: `kymora/workflow/test/src/io/eleven19/kymora/workflow/WorkflowTelemetryTests.scala`
- Modify: `kymora/workflow/README.md`

- [ ] **Step 1: Add state signal accessor test**

Extend `WorkflowTelemetryTests.scala`:

```scala
"WorkflowTelemetry.live exposes state changes for UI consumers" in {
  Scope.run {
    for
      telemetry <- WorkflowTelemetry.live()
      signal    <- telemetry.state
      before    <- signal.current
      _         <- telemetry.publish(WorkflowEvent.TaskQueued(TaskId("ui-task")))
      after     <- signal.current
    yield
      assert(before.tasks.isEmpty)
      assert(after.tasks(TaskId("ui-task")).status == WorkflowRunState.TaskStatus.Queued)
  }
}
```

- [ ] **Step 2: Run test and verify missing state API failure**

Run:

```sh
./mill --no-server kymora.workflow.jvm.test -k WorkflowTelemetryTests
```

Expected: compile failure mentioning missing `WorkflowTelemetry.state`.

- [ ] **Step 3: Add state signal API**

Update `WorkflowTelemetry.scala`:

```scala
def state(using Frame): Signal[WorkflowRunState] < Sync =
    Signal.initConst(WorkflowRunState.empty)
```

Override in `HubWorkflowTelemetry`:

```scala
override def state(using Frame): Signal[WorkflowRunState] < Sync =
    stateRef
```

- [ ] **Step 4: Run telemetry tests**

Run:

```sh
./mill --no-server kymora.workflow.jvm.test -k WorkflowTelemetryTests
```

Expected: all telemetry tests pass.

- [ ] **Step 5: Document event and state consumption**

Add to `kymora/workflow/README.md` under Observability:

```markdown
### Telemetry

`WorkflowTelemetry.live()` provides a scoped telemetry bus for live tools. It
publishes the same structured `WorkflowEvent` values used by observers, supports
multiple `Hub` listeners, and projects events into `WorkflowRunState` for UI
consumers:

```scala
Scope.run {
  for
    telemetry <- WorkflowTelemetry.live()
    runtime    = Workflow.Runtime(vfs = backend, telemetryOverride = Maybe(telemetry))
    listener  <- telemetry.listen()
    fiber     <- Fiber.init(listener.stream().runForeach(event => Console.printLine(event.toString)))
    result    <- Workflow.handle(runtime)(Workflow.run(goal))
    snapshot  <- telemetry.snapshot
    _         <- fiber.interruptDiscard
  yield result
}
```

Use `Workflow.inspect(goal)` when you only need the static dependency plan and
do not want to execute task bodies.
```

- [ ] **Step 6: Run README doctest**

Run:

```sh
./mill --no-server kymora.docs.jvm.doctest
```

Expected: doctest succeeds. If the docs runner cannot type-check scoped live telemetry snippets because of unavailable local `backend` or `goal` values, convert the example into a complete snippet that initializes an in-memory VFS and a small cached task.

- [ ] **Step 7: Checkpoint**

Run:

```sh
jj describe -m "Document live workflow telemetry"
```

---

### Task 10: Final Verification

**Files:**
- All files touched by Tasks 1-9.

- [ ] **Step 1: Run format**

Run:

```sh
./mill --no-server kymora.workflow.jvm.reformat
```

Expected: command succeeds.

- [ ] **Step 2: Run format check**

Run:

```sh
./mill --no-server kymora.workflow.jvm.checkFormat
```

Expected: command succeeds.

- [ ] **Step 3: Run workflow and testkit JVM tests**

Run:

```sh
./mill --no-server kymora.workflow.jvm.test kymora.workflow-testkit.jvm.test
```

Expected: all tests pass.

- [ ] **Step 4: Run cross-platform compile**

Run:

```sh
./mill --no-server kymora.workflow.js.compile kymora.workflow.wasm.compile kymora.workflow.native.compile
```

Expected: all platform compiles pass.

- [ ] **Step 5: Run docs doctest**

Run:

```sh
./mill --no-server kymora.docs.jvm.doctest
```

Expected: README snippets pass.

- [ ] **Step 6: Inspect final diff**

Run:

```sh
jj diff --stat
jj diff
```

Expected: diff is limited to workflow telemetry, planning, tests, docs, and dependency wiring.

- [ ] **Step 7: Final change description**

Run:

```sh
jj describe -m "Add workflow telemetry and inspection APIs"
```

---

## Self-Review

- Spec coverage:
  - Static inspect command foundation: Tasks 2 and 3 expose `WorkflowPlan` and `Workflow.inspect`.
  - Live task visibility: Tasks 5, 6, 7, 8, and 9 add telemetry publishing, hub listeners, state projection, and testkit access.
  - UI state reporting: Tasks 4 and 9 expose `WorkflowRunState` and `Signal[WorkflowRunState]`.
  - Current observer compatibility: Tasks 5 and 7 preserve observer forwarding.
  - Kyo primitive choice: Task 6 uses `Hub` for fanout and `Actor` for serialized projection; Task 9 exposes `Signal`.
- Placeholder scan:
  - No placeholder-only implementation steps are present.
  - Each code task includes exact file paths, test commands, and expected results.
- Type consistency:
  - `WorkflowPlan`, `WorkflowRunState`, `WorkflowTelemetry`, and `HubWorkflowTelemetry` names are consistent across tasks.
  - Runtime compatibility uses `telemetryOverride: Maybe[WorkflowTelemetry]` and computed `telemetry`.
  - Scheduler publishes `WorkflowEvent` through `WorkflowTelemetry.publish`.
