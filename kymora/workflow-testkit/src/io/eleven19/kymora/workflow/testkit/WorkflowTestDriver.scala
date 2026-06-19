package io.eleven19.kymora.workflow.testkit

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.store.*
import io.eleven19.kymora.vfs.*
import kyo.*

/** End-to-end test harness for kymora-workflow.
  *
  * Composes the runtime [[Workflow.run]] needs:
  *   - a fresh in-memory [[Vfs]] (also exposed as [[vfs]])
  *   - a [[VfsDirStore]] over that VFS (also exposed as [[store]])
  *   - a fresh [[CollectingObserver]] (also exposed as [[observer]])
  *   - a live [[WorkflowTelemetry]] snapshot source (also exposed as [[telemetry]])
  *   - a [[Workflow.Config]] with parallelism = 1 for deterministic event ordering (also exposed as [[config]])
  *
  * Usage:
  * {{{
  *   for
  *     driver <- WorkflowTestDriver.init
  *     value  <- driver.run(goal)
  *     events <- driver.events
  *   yield (value, events)
  * }}}
  *
  * `driver.run` calls [[Workflow.handle]] so the test body doesn't have to.
  */
final class WorkflowTestDriver private (
    val config: Workflow.Config,
    val vfs: Vfs.Backend,
    val store: CacheStore,
    val observer: CollectingObserver,
    val telemetry: WorkflowTelemetry
):

    def runtime: Workflow.Runtime =
        Workflow.Runtime(config, vfs, VPath("cache"), observer, Json(), Maybe(telemetry))

    /** All [[WorkflowEvent]]s captured so far, in emission order. */
    def events: Chunk[WorkflowEvent] < Async = observer.events

    /** Execute a goal under this driver's runtime via [[Workflow.run]]. */
    def run[A](goal: Task[A])(using
        Frame
    ): A < (Async & Abort[WorkflowError]) =
        Workflow.handle(runtime)(Workflow.run(goal))

end WorkflowTestDriver

object WorkflowTestDriver:

    /** Constructs a fresh driver wired with an in-memory [[Vfs]], a [[VfsDirStore]] over it, a [[CollectingObserver]],
      * live [[WorkflowTelemetry]], and [[Workflow.Config.default]] (parallelism forced to 1 for deterministic event
      * ordering in tests).
      */
    def init(using Frame): WorkflowTestDriver < (Async & Scope & Abort[StoreError]) =
        for
            vfs      <- Vfs.inMemory.init
            store    <- VfsDirStore.init(VPath("cache"), vfs)
            observer <- CollectingObserver.init
            live     <- TestWorkflowTelemetry.init
            serial   <- Meter.initMutexUnscoped
            telemetry = FanoutWorkflowTelemetry(live, observer.asTelemetry, serial)
            cfg       = Workflow.Config.default.copy(parallelism = 1)
        yield new WorkflowTestDriver(cfg, vfs, store, observer, telemetry)

end WorkflowTestDriver

final private class TestWorkflowTelemetry private (
    hub: Hub[WorkflowEvent],
    stateRef: SignalRef[WorkflowRunState],
    serial: Meter
) extends WorkflowTelemetry:

    override def publish(event: WorkflowEvent)(using Frame): Unit < Async =
        Abort.recover[Closed](_ => ())(serial.run {
            for
                published <- Abort.run[Closed](hub.put(event))
                _ <- published match
                    case Result.Success(_) =>
                        stateRef.updateAndGet(_.applyEvent(event)).unit
                    case Result.Failure(_) =>
                        Kyo.unit
                    case Result.Panic(ex) =>
                        Abort.panic(ex)
            yield ()
        })

    override def snapshot(using Frame): WorkflowRunState < Async =
        stateRef.current

    override def state(using Frame): Signal[WorkflowRunState] < Sync =
        stateRef

    override def listen(bufferSize: Int)(using
        Frame
    ): Hub.Listener[WorkflowEvent] < (Sync & Scope & Abort[Closed]) =
        hub.listen(bufferSize)

end TestWorkflowTelemetry

private object TestWorkflowTelemetry:

    def init(using Frame): WorkflowTelemetry < (Sync & Scope) =
        for
            hub      <- Hub.init[WorkflowEvent](WorkflowTelemetry.DefaultBufferSize)
            stateRef <- Signal.initRef(WorkflowRunState.empty)
            serial   <- Meter.initMutexUnscoped
        yield new TestWorkflowTelemetry(hub, stateRef, serial)

final private class FanoutWorkflowTelemetry(
    live: WorkflowTelemetry,
    observer: WorkflowTelemetry,
    serial: Meter
) extends WorkflowTelemetry:

    override def publish(event: WorkflowEvent)(using Frame): Unit < Async =
        Abort.recover[Closed](_ => ())(serial.run:
            live.publish(event).andThen(observer.publish(event)))

    override def snapshot(using Frame): WorkflowRunState < Async =
        live.snapshot

    override def state(using Frame): Signal[WorkflowRunState] < Sync =
        live.state

    override def listen(bufferSize: Int)(using
        frame: Frame
    ): Hub.Listener[WorkflowEvent] < (Sync & Scope & Abort[Closed]) =
        live.listen(bufferSize)

end FanoutWorkflowTelemetry
