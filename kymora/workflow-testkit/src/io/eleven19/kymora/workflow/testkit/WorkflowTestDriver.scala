package io.eleven19.kymora.workflow.testkit

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.store.*
import io.eleven19.kymora.vfs.*
import kyo.*

/** End-to-end test harness for kymora-workflow.
  *
  * Composes the four services [[Workflow.run]] needs:
  *   - a fresh in-memory [[Vfs]] (also exposed as [[vfs]])
  *   - a [[VfsDirStore]] over that VFS (also exposed as [[store]])
  *   - a fresh [[CollectingObserver]] (also exposed as [[observer]])
  *   - a [[Workflow.Config]] with parallelism = 1 for deterministic event
  *     ordering (also exposed as [[config]])
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
  * `driver.run` chains the four `Env.run` layers internally via
  * [[Workflow.Services.provide]] so the test body doesn't have to.
  */
final class WorkflowTestDriver private (
    val config: Workflow.Config,
    val vfs: Vfs,
    val store: CacheStore,
    val observer: CollectingObserver,
):

  /** The four wired services as a single [[Workflow.Services.Bundle]].
    * Pass to [[Workflow.Services.provide]] to layer them onto any effect
    * requiring `Workflow.Services`. */
  def services: Workflow.Services.Bundle =
    Workflow.Services.init(config, vfs, store, observer)

  /** All [[WorkflowEvent]]s captured so far, in emission order. */
  def events: Chunk[WorkflowEvent] < Async = observer.events

  /** Execute a goal under this driver's services via [[Workflow.run]]. */
  def run[A](goal: Task[A])(using
      Frame,
  ): A < (Async & Abort[WorkflowError]) =
    Workflow.Services.provide(services)(Workflow.run(goal))

end WorkflowTestDriver

object WorkflowTestDriver:

  /** Constructs a fresh driver wired with an in-memory [[Vfs]], a
    * [[VfsDirStore]] over it, a [[CollectingObserver]], and
    * [[Workflow.Config.default]] (parallelism forced to 1 for deterministic
    * event ordering in tests). */
  def init(using Frame): WorkflowTestDriver < (Async & Abort[StoreError]) =
    for
      vfs      <- Vfs.inMemory.init
      store    <- VfsDirStore.init(VPath("cache"), vfs)
      observer <- CollectingObserver.init
      cfg       = Workflow.Config.default.copy(parallelism = 1)
    yield new WorkflowTestDriver(cfg, vfs, store, observer)

end WorkflowTestDriver
