# Workflow Telemetry Pub/Sub Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace workflow telemetry's `Hub + Meter` implementation with Kyo actor/pub-sub, expose a read-only `WorkflowTelemetry.subscribe` API, and remove the leaked `Hub.Listener` API while preserving ordering and lifecycle guarantees.

**Architecture:** `WorkflowTelemetry.live` builds `PubSubWorkflowTelemetry`, which owns a `PubSub.linearized[WorkflowEvent]`, a `SignalRef[WorkflowRunState]`, and a private actor that serializes whole publish operations. Public subscriptions are `WorkflowTelemetry.Subscription` wrappers over scoped channels subscribed to the internal PubSub.

**Tech Stack:** Scala 3.8.4, Mill, kyo-core, kyo-actor, kyo-test, jj.

---

## Reference Inputs

- Design spec: `docs/superpowers/specs/2026-06-27-workflow-telemetry-pubsub-design.md`
- Kyo reference checkout: `.ref/getkyo/kyo` at `2eafb7d1c426877c785c15cf5ead8bffdfad0e80`
- Kyo files to consult during implementation:
  - `.ref/getkyo/kyo/kyo-actor/shared/src/main/scala/kyo/Actor.scala`
  - `.ref/getkyo/kyo/kyo-actor/shared/src/main/scala/kyo/PubSub.scala`
  - `.ref/getkyo/kyo/kyo-actor/shared/src/test/scala/kyo/ActorTest.scala`
  - `.ref/getkyo/kyo/kyo-actor/shared/src/test/scala/kyo/PubSubTest.scala`

## Files To Modify

- `kymora/workflow/src/io/eleven19/kymora/workflow/WorkflowTelemetry.scala`
- `kymora/workflow/src/io/eleven19/kymora/workflow/internal/HubWorkflowTelemetry.scala`
- `kymora/workflow/src/io/eleven19/kymora/workflow/internal/PubSubWorkflowTelemetry.scala`
- `kymora/workflow/test/src/io/eleven19/kymora/workflow/WorkflowTelemetryTests.scala`
- `kymora/workflow-testkit/src/io/eleven19/kymora/workflow/testkit/WorkflowTestDriver.scala`
- `kymora/workflow-testkit/test/src/io/eleven19/kymora/workflow/testkit/WorkflowTestDriverTests.scala`
- `kymora/workflow/package.mill`
- `kymora/workflow/README.md`
- `docs/superpowers/plans/2026-06-19-kyo-actor-telemetry-issue.md`

## Step 1: Replace `listen` With `subscribe`

- [ ] Update tests first in `WorkflowTelemetryTests.scala` and `WorkflowTestDriverTests.scala`.

Change all public telemetry listener usage from `listen` to `subscribe`. Add a direct read-only subscription test:

```scala
"live exposes a read-only subscription handle" in {
    val event = WorkflowEvent.TaskQueued(TaskId("subscribe-api"))

    Scope.run {
        for
            telemetry    <- WorkflowTelemetry.live(bufferSize = 8)
            subscription <- telemetry.subscribe(bufferSize = 8)
            _            <- telemetry.publish(event)
            seen         <- subscription.take
        yield assert(seen == event)
    }
}
```

Run:

```sh
./mill --no-server kymora.workflow.jvm.test -k WorkflowTelemetryTests
./mill --no-server kymora.workflow-testkit.jvm.test -k WorkflowTestDriverTests
```

Expected failure: compile errors saying `subscribe` is not a member of `WorkflowTelemetry`.

- [ ] Implement the public API in `WorkflowTelemetry.scala`.

Add:

```scala
object WorkflowTelemetry:
    trait Subscription:
        def take(using Frame): WorkflowEvent < (Async & Abort[Closed])
        def takeExactly(n: Int)(using Frame): Chunk[WorkflowEvent] < (Async & Abort[Closed])
        def drainUpTo(max: Int)(using Frame): Chunk[WorkflowEvent] < (Sync & Abort[Closed])
```

Replace the trait's default `listen` method with:

```scala
def subscribe(bufferSize: Int = WorkflowTelemetry.DefaultBufferSize)(using
    frame: Frame
): WorkflowTelemetry.Subscription < (Async & Scope & Abort[Closed]) =
    Abort.fail(Closed("WorkflowTelemetry.subscribe", frame))
```

Do not keep `listen`.

- [ ] Temporarily adapt the current Hub-backed implementation so the API migration compiles before replacing internals.

In `HubWorkflowTelemetry.scala`, override `subscribe` and return a private `WorkflowTelemetry.Subscription` wrapper around `Hub.Listener[WorkflowEvent]`. This wrapper is temporary and will be deleted in Step 2 with the old Hub implementation.

In `WorkflowTestDriver.scala`, update `FanoutWorkflowTelemetry` and `TestWorkflowTelemetry` to override `subscribe`.

- [ ] Verify:

```sh
./mill --no-server kymora.workflow.jvm.test -k WorkflowTelemetryTests
./mill --no-server kymora.workflow-testkit.jvm.test -k WorkflowTestDriverTests
rg -n "listen\\(" kymora/workflow/src kymora/workflow/test kymora/workflow-testkit/src kymora/workflow-testkit/test
```

Expected: the focused tests pass and `rg` finds no source/test usage under those modules.

## Step 2: Add `kyo-actor` And Replace The Internal Telemetry

- [ ] Add migration tests and replacement checks.

Extend `WorkflowTelemetryTests.scala` with closed-subscription pruning coverage:

```scala
"live prunes closed subscriptions without blocking live subscriptions" in {
    val event = WorkflowEvent.TaskQueued(TaskId("live-subscription"))

    Scope.run {
        for
            telemetry <- WorkflowTelemetry.live(bufferSize = 8)
            _         <- Scope.run(telemetry.subscribe(bufferSize = 8))
            live      <- telemetry.subscribe(bufferSize = 8)
            _         <- telemetry.publish(event)
            seen      <- live.take
        yield assert(seen == event)
    }
}
```

Run:

```sh
./mill --no-server kymora.workflow.jvm.test -k WorkflowTelemetryTests
test ! -e kymora/workflow/src/io/eleven19/kymora/workflow/internal/PubSubWorkflowTelemetry.scala
```

Expected: the new behavior test either passes against the compatibility Hub implementation or exposes a current lifecycle gap. The source check succeeds before replacement and will be inverted after implementation to prove the new internal class exists.

- [ ] Update `kymora/workflow/package.mill`.

Add `kyo-actor` to `WorkflowModule.mvnDeps`:

```scala
mvn"io.getkyo::kyo-actor::${_root_.build.KymoraVersions.Kyo}",
```

Add the explicit Wasm dependency in `object wasm`:

```scala
mvn"io.getkyo:kyo-actor_sjs1_3:${_root_.build.KymoraVersions.Kyo}",
```

- [ ] Replace `HubWorkflowTelemetry` with `PubSubWorkflowTelemetry`.

Delete `HubWorkflowTelemetry.scala`.

Create `PubSubWorkflowTelemetry.scala` with:

- a `final private[workflow] class PubSubWorkflowTelemetry`
- a private `enum Command` with `Publish(event: WorkflowEvent, replyTo: Actor.Subject[Unit])`
- a private channel-backed `WorkflowTelemetry.Subscription` implementation
- `init(bufferSize)` that creates `PubSub.linearized[WorkflowEvent]`, `Signal.initRef(WorkflowRunState.empty)`, and an actor with mailbox capacity `bufferSize`

Publish handling order must be:

1. `pubSub.publish(event)`
2. `stateRef.updateAndGet(_.applyEvent(event)).unit`
3. `replyTo.send(())`
4. `Loop.continue`

`publish(event)` must call `publisher.ask(Command.Publish(event, _))` and recover `Closed` to `Kyo.unit`.

`subscribe(bufferSize)` must create a bounded `Channel[WorkflowEvent]`, subscribe `Actor.Subject.init(channel)` to the internal PubSub, and return the read-only wrapper.

- [ ] Point `WorkflowTelemetry.live` at `PubSubWorkflowTelemetry.init`.

In `WorkflowTelemetry.scala`, replace:

```scala
internal.HubWorkflowTelemetry.init(bufferSize)
```

with:

```scala
internal.PubSubWorkflowTelemetry.init(bufferSize)
```

- [ ] Verify:

```sh
./mill --no-server kymora.workflow.jvm.test -k WorkflowTelemetryTests
rg -n "HubWorkflowTelemetry|Hub\\.init|Meter\\.initMutexUnscoped|def listen|Hub\\.Listener" kymora/workflow/src kymora/workflow/test
rg -n "PubSub\\.linearized|Actor\\.run|\\.ask\\(" kymora/workflow/src/io/eleven19/kymora/workflow/internal/PubSubWorkflowTelemetry.scala
```

Expected: focused workflow telemetry tests pass; no old Hub/listen implementation references remain in workflow source/test; the new file contains PubSub and Actor usage.

## Step 3: Simplify The Testkit Around Live Telemetry

- [ ] Add or adjust testkit tests first.

In `WorkflowTestDriverTests.scala`, keep the existing telemetry snapshot/order tests and update the subscription test to use `subscribe`:

```scala
"WorkflowTestDriver telemetry supports subscriptions" in {
    val event = WorkflowEvent.TaskQueued(TaskId("driver-subscription"))

    Scope.run {
        for
            driver       <- WorkflowTestDriver.init
            subscription <- driver.telemetry.subscribe(bufferSize = 8)
            _            <- driver.telemetry.publish(event)
            seen         <- subscription.take
            events       <- driver.events
        yield
            assert(seen == event)
            assert(events == Chunk(event))
    }
}
```

Run:

```sh
./mill --no-server kymora.workflow-testkit.jvm.test -k WorkflowTestDriverTests
```

Expected before implementation: this may already pass after Step 1. Treat it as a characterization test before deleting the private Hub-backed test telemetry helper.

- [ ] Update `WorkflowTestDriver.scala`.

Remove private `TestWorkflowTelemetry`; construct `live <- WorkflowTelemetry.live()` directly in `WorkflowTestDriver.init`.

Keep `FanoutWorkflowTelemetry` only as the bridge that publishes to live telemetry and the collecting observer under its existing `Meter`. Update it to delegate `subscribe` to `live.subscribe`.

- [ ] Verify:

```sh
./mill --no-server kymora.workflow-testkit.jvm.test -k WorkflowTestDriverTests
rg -n "TestWorkflowTelemetry|listen\\(|Hub\\.Listener|Hub\\.init" kymora/workflow-testkit/src kymora/workflow-testkit/test
```

Expected: testkit focused tests pass and old helper/listener references are gone.

## Step 4: Update Docs And Historical Plan

- [ ] Update `kymora/workflow/README.md`.

Replace the telemetry example:

```scala
listener <- telemetry.listen()
firstEvent <- listener.take
```

with:

```scala
subscription <- telemetry.subscribe()
firstEvent <- subscription.take
```

Use “subscription” terminology in nearby prose.

- [ ] Update `docs/superpowers/plans/2026-06-19-kyo-actor-telemetry-issue.md`.

Keep it as historical context and add a short note at the top:

```md
> Historical note: the Kyo actor issue described here was resolved upstream and
> Kymora replaced this workaround in `2026-06-27-workflow-telemetry-pubsub.md`.
```

- [ ] Verify no stale public references remain:

```sh
rg -n "listen\\(|HubWorkflowTelemetry|Hub\\.Listener|kyo actor issue draft" kymora docs README.md
```

Expected: no live API references remain. Historical mentions are acceptable only inside the two dated plan/spec documents.

## Step 5: Full Verification

- [ ] Run JVM workflow and testkit suites:

```sh
./mill --no-server kymora.workflow.jvm.test
./mill --no-server kymora.workflow-testkit.jvm.test
```

- [ ] Run cross-platform workflow verification:

```sh
./mill --no-server kymora.workflow.js.test
./mill --no-server kymora.workflow.wasm.test
./mill --no-server kymora.workflow.native.test
```

If Wasm or Native tooling fails for environment reasons, run the corresponding compile tasks and record the exact failing toolchain message:

```sh
./mill --no-server kymora.workflow.wasm.compile
./mill --no-server kymora.workflow.native.compile
```

- [ ] Run source checks:

```sh
rg -n "listen\\(" kymora
rg -n "HubWorkflowTelemetry|Hub\\.Listener" kymora/workflow kymora/workflow-testkit
rg -n "kyo-actor" kymora/workflow/package.mill
```

Expected: no `listen` calls remain in Kymora source/tests/docs except historical plan text; no old Hub telemetry type references remain; `kyo-actor` is declared for common and Wasm workflow dependencies.

## Step 6: Commit And PR Prep

- [ ] Describe the jj working copy:

```sh
jj describe -m "Use Kyo pubsub for workflow telemetry"
```

- [ ] Review the diff:

```sh
jj diff --stat
jj diff
```

- [ ] Create a bookmark and push:

```sh
jj bookmark create workflow-telemetry-pubsub -r @
jj git push --bookmark workflow-telemetry-pubsub
```

- [ ] Open the PR:

```sh
gh pr create --base main --head workflow-telemetry-pubsub --title "Use Kyo pubsub for workflow telemetry" --body-file /tmp/kymora-workflow-telemetry-pubsub-pr.md
```

PR body must mention:

- Issue #30
- `listen` to `subscribe` API change
- Kyo actor/pub-sub dependency
- Verification commands and any platform limitations

## Completion Criteria

- Public API uses `WorkflowTelemetry.subscribe` and `WorkflowTelemetry.Subscription`.
- `WorkflowTelemetry.listen` is removed.
- Workflow live telemetry uses Kyo actor/pub-sub, not `HubWorkflowTelemetry`.
- Publish preserves snapshot-after-publish and subscription/snapshot order alignment under concurrent publishers.
- Publish after shutdown and publish racing shutdown complete without hanging.
- Testkit uses the same live telemetry implementation as production.
- README uses subscription terminology.
- JVM tests pass; cross-platform tests or compiles are attempted and results are reported.
