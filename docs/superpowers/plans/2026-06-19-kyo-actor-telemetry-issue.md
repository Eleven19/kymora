# Kyo Actor Issue Draft: Safe Request/Ack Publishing For Scoped Actors

Filed upstream as getkyo/kyo#1688: <https://github.com/getkyo/kyo/issues/1688>.
Kymora follow-up issue: Eleven19/kymora#30: <https://github.com/Eleven19/kymora/issues/30>.

## Summary

Kymora workflow telemetry initially used a Kyo `Actor` to serialize event fanout and state projection:

1. callers published `WorkflowEvent`s through `publisher.ask(Publish(event, _))`
2. the actor wrote the event to a `Hub[WorkflowEvent]`
3. after a successful hub publish, the actor updated a `SignalRef[WorkflowRunState]`
4. the actor replied to the caller so `publish(event)` could guarantee `snapshot` was current after it returned

This shape matched the domain well, but we replaced it with a `Meter` mutex because the current actor API made it too easy
to strand request/ack callers during scoped shutdown or panic paths.

## Required Semantics

Workflow telemetry publish needs these guarantees:

- **Linearized publish:** Hub listeners and projected state must observe events in the same order, even with concurrent publishers.
- **Snapshot-after-publish:** if `publish(event)` returns successfully while telemetry is open, `snapshot.events` includes `event`.
- **Closed telemetry no-op:** publishing after scope shutdown must complete without hanging and must not mutate projected state.
- **No stranded callers:** a caller waiting for an actor reply must always complete with success, failure, closed, interruption, or panic.
- **Panic propagation:** if hub/state projection panics, the publishing caller should observe the panic or a failed result; it should not wait forever.

## Actor Attempt

The actor-based implementation looked like this conceptually:

```scala
private final case class Publish(event: WorkflowEvent, replyTo: Actor.Subject[Unit])

override def publish(event: WorkflowEvent)(using Frame): Unit < Async =
  Abort.recover[Closed](_ => ())(publisher.ask(Publish(event, _)))

publisher <- Actor.run(bufferSize) {
  Actor.receiveLoop[Publish] { publish =>
    for
      published <- Abort.run[Closed](hub.put(publish.event))
      _ <- published match
             case Result.Success(_) => stateRef.updateAndGet(_.applyEvent(publish.event)).unit
             case Result.Failure(_) => Kyo.unit
             case Result.Panic(ex)  => Abort.panic(ex)
      _ <- Abort.recover[Closed](_ => ())(publish.replyTo.send(()))
    yield Loop.continue
  }
}
```

## Problems Observed In Design Review

### 1. Scoped shutdown can strand `ask`

`Actor.ask` creates a reply subject/promise and sends a request. During scoped actor shutdown, the mailbox can close or
the consumer fiber can be interrupted while accepted `Publish` messages are still queued. If a queued message never
reaches the handler, the handler never sends to `replyTo`, and the publishing fiber can wait forever.

For telemetry, a closed scope should make `publish(event)` a whole-publish no-op. It should not hang.

### 2. Panic before reply can strand `ask`

If `hub.put` returns `Result.Panic` or `stateRef.updateAndGet(_.applyEvent(event))` panics before the reply send, the actor
can terminate before completing the reply subject. The publishing fiber can then wait forever instead of observing the
panic.

For telemetry, panic should be delivered to the publishing fiber or represented as a failed result.

### 3. Separate hub/projector operations were not enough

An earlier attempt did `hub.put(event)` and then sent a projector message. That avoided `ask` in one path, but concurrent
publishers could enqueue to the hub in one order and the projector in another order. That made listener order and
`snapshot.events` order diverge.

The useful actor abstraction here is not just background state projection. It needs request/ack publishing with shutdown
and panic semantics that keep caller completion coupled to message handling.

## Workaround Shipped In Kymora

Kymora currently uses a `Meter` mutex with `Hub` and `SignalRef`:

```scala
override def publish(event: WorkflowEvent)(using Frame): Unit < Async =
  Abort.recover[Closed](_ => ())(serial.run {
    for
      published <- Abort.run[Closed](hub.put(event))
      _ <- published match
             case Result.Success(_) => stateRef.updateAndGet(_.applyEvent(event)).unit
             case Result.Failure(_) => Kyo.unit
             case Result.Panic(ex)  => Abort.panic(ex)
    yield ()
  })
```

This preserves ordering and snapshot-after-publish, treats closed hub publish as no-op, and has no per-message reply
promise to strand. It is less actor-shaped but currently has safer lifecycle behavior for this use case.

## Requested Kyo Enhancement

One of these would make the actor approach viable:

- `Actor.ask` should complete with `Closed` or interruption if the actor closes after accepting/enqueuing a message but
  before processing it.
- Actor shutdown could drain or reject queued ask messages and complete their reply subjects.
- Provide a request/reply actor helper that accepts a handler returning a `Result` and guarantees reply completion before
  actor termination.
- Provide a documented pattern for scoped request/ack actors where caller waiters cannot be stranded by scope finalizers,
  mailbox close, handler interruption, or handler panic.

## Acceptance Test Sketch

```scala
"ask completes when scoped actor closes with queued messages" in {
  val result =
    for
      fiber <- Scope.run {
                 for
                   actor <- Actor.run(capacity = 1) {
                              Actor.receiveLoop[Request] { request =>
                                request.replyTo.send(()).andThen(Loop.continue)
                              }
                            }
                   fiber <- Fiber.initUnscoped(actor.ask(Request(_)))
                 yield fiber
               }
      result <- Abort.run[Closed](Async.timeout(2.seconds)(fiber.get))
    yield result

  assert(result.isSuccess || result.isFailure)
}
```

The exact test should use Kyo's preferred timeout/interruption assertions, but the important requirement is that there is
no indefinitely pending fiber.
