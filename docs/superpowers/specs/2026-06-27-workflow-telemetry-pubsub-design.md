# Workflow Telemetry Pub/Sub Design

## Context

Issue #30 tracks replacing workflow telemetry's current `Hub + SignalRef + Meter` implementation now that the pinned Kyo snapshot includes actor pub/sub support and strand-safe `Actor.ask`.

The current telemetry implementation intentionally uses a `Meter` mutex around `Hub.put(event)` and `SignalRef.updateAndGet(_.applyEvent(event))`. That preserved the required semantics while Kyo actor request/ack callers could be stranded if the actor closed or panicked before replying.

The Kyo reference checkout pinned for this repo is `getkyo/kyo@2eafb7d1c426877c785c15cf5ead8bffdfad0e80`. That source includes:

- `Actor.ask`, which registers pending replies and completes them when the actor terminates.
- `PubSub.linearized`, which serializes publishes and subscription changes through an internal actor.
- Kyo tests covering no-stranded actor ask, no-stranded linearized pub/sub publish during close, and total-order delivery to subscribers under concurrent publishers.

## Goals

- Replace the internal telemetry serialization workaround with an actor/pub-sub implementation backed by the released Kyo snapshot.
- Keep `WorkflowTelemetry.publish` returning only after live subscribers have accepted the event and `snapshot` reflects it.
- Preserve linearized ordering between subscriber observations and `WorkflowRunState.events`.
- Keep deterministic no-op behavior for publish after telemetry scope shutdown.
- Avoid stranded publish callers when telemetry closes or the telemetry actor panics.
- Improve the public listener API for pre-1.0 DX instead of preserving `Hub.Listener`.

## Non-Goals

- Do not expose Kyo `PubSub` directly as Kymora's telemetry API.
- Do not keep `WorkflowTelemetry.listen` solely for source compatibility.
- Do not change workflow event types or `WorkflowRunState` projection semantics.
- Do not move test helpers out of `workflow-testkit` unless a helper is generally useful downstream.

## Public API

Replace `WorkflowTelemetry.listen` with `WorkflowTelemetry.subscribe`.

```scala
trait WorkflowTelemetry:
    def publish(event: WorkflowEvent)(using Frame): Unit < Async

    def snapshot(using Frame): WorkflowRunState < Async =
        WorkflowRunState.empty

    def state(using Frame): Signal[WorkflowRunState] < Sync =
        Signal.initConst(WorkflowRunState.empty)

    def subscribe(bufferSize: Int = WorkflowTelemetry.DefaultBufferSize)(using
        Frame
    ): WorkflowTelemetry.Subscription < (Async & Scope & Abort[Closed]) =
        Abort.fail(Closed("WorkflowTelemetry.subscribe", frame))
end WorkflowTelemetry
```

Add a read-only subscription handle:

```scala
object WorkflowTelemetry:
    trait Subscription:
        def take(using Frame): WorkflowEvent < (Async & Abort[Closed])
        def takeExactly(n: Int)(using Frame): Chunk[WorkflowEvent] < (Async & Abort[Closed])
        def drainUpTo(max: Int)(using Frame): Chunk[WorkflowEvent] < (Sync & Abort[Closed])
```

The subscription API is intentionally not `Channel[WorkflowEvent]`. A raw channel would expose write capability to consumers. A Kymora-specific read-only wrapper keeps the API simple while preserving future freedom to change the backing primitive.

## Internal Architecture

Replace `HubWorkflowTelemetry` with `PubSubWorkflowTelemetry`.

`PubSubWorkflowTelemetry` owns:

- a `PubSub[WorkflowEvent]` created with `PubSub.linearized[WorkflowEvent]`
- a `SignalRef[WorkflowRunState]`
- a telemetry actor that serializes whole publish operations

The telemetry actor receives this command:

```scala
private enum Command:
    case Publish(event: WorkflowEvent, replyTo: Actor.Subject[Unit])
```

For each publish command, the actor:

1. calls `pubSub.publish(event)`
2. updates `stateRef` with `_.applyEvent(event)`
3. replies to the caller

`WorkflowTelemetry.publish(event)` sends `Command.Publish` with lifecycle-safe `Actor.ask`. It recovers `Closed` to `Kyo.unit` so publish after shutdown remains a whole-publish no-op.

This actor is still needed even though `PubSub.linearized` exists. `PubSub.linearized` serializes fanout to subscribers, but Kymora also needs `WorkflowRunState` projection to share the same total order and to be complete before `publish` returns. A telemetry-owned actor is the single serialization point for fanout plus state projection.

## Subscription Flow

`subscribe(bufferSize)` creates a bounded `Channel[WorkflowEvent]`, subscribes its `Actor.Subject` to the internal `PubSub`, and returns a `WorkflowTelemetry.Subscription` wrapper over the channel.

The subscription is removed by `PubSub.subscribe` when the caller's enclosing scope closes. The channel is not exposed for writes. Consumers can read individual events, read an exact count, or drain currently buffered events.

## Error And Lifecycle Semantics

- If telemetry is open and `publish(event)` returns successfully, `snapshot.events` includes `event`.
- If telemetry is closed before publish is accepted, `publish(event)` completes as a no-op.
- If telemetry closes while publish is in flight, `Actor.ask` completes with success or `Closed`; Kymora recovers `Closed` to no-op.
- If subscriber delivery finds a closed or failing subscription, Kyo `PubSub` prunes it and continues delivering to live subscribers.
- If state projection panics, the publish caller observes the panic rather than waiting forever.
- `subscribe` after telemetry shutdown fails with `Closed`.

## Validation Plan

Keep and adapt existing telemetry tests:

- from-observer forwarding
- no-op telemetry publish
- live fanout to two subscribers through `subscribe`
- snapshot and signal projection
- real workflow bracketing and cache aggregate snapshots
- concurrent publisher order alignment between subscription events and `snapshot.events`
- publish after telemetry scope shutdown completes as no-op
- publish racing telemetry scope shutdown completes within timeout

Add Kymora-specific validation for the Kyo fix:

- a subscription created before concurrent publishes sees the same total order as `snapshot.events`
- a publish that races telemetry actor shutdown cannot strand
- a telemetry actor failure propagates to the publish caller or completes with a failure, never a timeout
- a closed subscription is pruned without blocking delivery to live subscriptions

Run at least:

```sh
./mill kymora.workflow.jvm.test
```

Because `kyo-actor` is cross-platform and workflow publishes JVM, JS, Wasm, and Native artifacts, compile or test changed platforms before completion:

```sh
./mill kymora.workflow.js.test
./mill kymora.workflow.wasm.test
./mill kymora.workflow.native.test
```

If Native or Wasm tooling is unavailable or too slow locally, run the corresponding compile task and document the limitation.

## Dependency Changes

Add `kyo-actor` to `kymora/workflow/package.mill` for all workflow platforms. Direct artifact checks against the pinned snapshot confirmed these artifacts exist:

- `io.getkyo:kyo-actor_3:1.0.0-RC4+49-2eafb7d1-SNAPSHOT`
- `io.getkyo:kyo-actor_sjs1_3:1.0.0-RC4+49-2eafb7d1-SNAPSHOT`
- `io.getkyo:kyo-actor_native0.5_3:1.0.0-RC4+49-2eafb7d1-SNAPSHOT`

The Wasm workflow module already spells out Scala.js dependencies explicitly, so it should add `kyo-actor_sjs1_3` in that platform-specific dependency list.

## Documentation Changes

Update workflow README examples from `listen` to `subscribe`.

Update or remove `docs/superpowers/plans/2026-06-19-kyo-actor-telemetry-issue.md` once implementation validates the released Kyo behavior. If kept, mark it as historical context and link to the replacement implementation plan.

## Implementation Notes

- The implementation plan may choose exact internal file and private command names, but the public API names are fixed by this design: `WorkflowTelemetry.subscribe` and `WorkflowTelemetry.Subscription`.
- `listen` should be removed rather than deprecated because Kymora is pre-1.0 and the old return type leaks an implementation detail.
