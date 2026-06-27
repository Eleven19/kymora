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
            live     <- WorkflowTelemetry.live()
            serial   <- Meter.initMutexUnscoped
            telemetry = FanoutWorkflowTelemetry(live, observer.asTelemetry, serial)
            cfg       = Workflow.Config.default.copy(parallelism = 1)
        yield new WorkflowTestDriver(cfg, vfs, store, observer, telemetry)

end WorkflowTestDriver

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

    override def subscribe(bufferSize: Int)(using
        frame: Frame
    ): WorkflowTelemetry.Subscription < (Async & Scope & Abort[Closed]) =
        live.subscribe(bufferSize)

end FanoutWorkflowTelemetry
