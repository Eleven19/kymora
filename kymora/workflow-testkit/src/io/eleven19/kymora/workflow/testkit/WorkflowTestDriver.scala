package io.eleven19.kymora.workflow.testkit

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.store.*
import kyo.*

/** End-to-end test harness for kymora-workflow.
  *
  * Composes the three testkit primitives:
  *   - [[InMemoryCacheStore]] (the [[CacheStore]])
  *   - [[CollectingObserver]] (captures the [[WorkflowEvent]] stream)
  *   - [[TestClock]]-controlled time (installed via `TestClock.run` from the
  *     outer block)
  *
  * Usage:
  * {{{
  *   for
  *     driver <- WorkflowTestDriver.init
  *     value  <- Env.run(driver.config)(driver.run(goal))
  *   yield value
  * }}}
  */
final class WorkflowTestDriver private (
    val store: CacheStore,
    val observer: CollectingObserver,
    val config: Workflow.Config,
):

  /** All [[WorkflowEvent]]s captured so far, in emission order. */
  def events: Chunk[WorkflowEvent] < Async = observer.events

  /** Execute a goal under this driver's [[config]] via [[Workflow.run]]. */
  def run[A](goal: Task[A])(using
      Frame,
  ): A < (Async & Env[Workflow.Config] & Abort[WorkflowError]) =
    Workflow.run(goal)

end WorkflowTestDriver

object WorkflowTestDriver:

  /** Constructs a fresh driver wired with an [[InMemoryCacheStore]], a
    * fresh [[CollectingObserver]], and a [[Workflow.Config]] composed from the
    * two.
    */
  def init(using Frame): WorkflowTestDriver < (Async & Abort[StoreError]) =
    for
      store    <- InMemoryCacheStore.init
      observer <- CollectingObserver.init
      cfg       = Workflow.Config(
                    store       = store,
                    codec       = Json(),
                    parallelism = 1,
                    observer    = observer,
                  )
    yield new WorkflowTestDriver(store, observer, cfg)

end WorkflowTestDriver
