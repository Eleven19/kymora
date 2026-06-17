package io.eleven19.kymora.workflow.testkit

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.store.*
import kyo.*

/** End-to-end test harness for kymora-workflow.
  *
  * Composes the three testkit primitives:
  *   - [[InMemoryCacheStore]] (the [[CacheStore]])
  *   - [[TestReporter]] (captures the [[WorkflowEvent]] stream)
  *   - [[TestClock]]-controlled time (installed via `TestClock.run` from the
  *     outer block)
  *
  * Usage:
  * {{{
  *   TestClock.run { tc =>
  *     for
  *       driver <- WorkflowTestDriver.init
  *       _      <- driver.reporter.onEvent(...)
  *       events <- driver.events
  *     yield events
  *   }
  * }}}
  *
  * The [[run]] execution entry point is a stub until Phase 11 lands
  * `Workflow.run`.
  */
final class WorkflowTestDriver private (
    val store: CacheStore,
    val reporter: TestReporter,
):

  /** All [[WorkflowEvent]]s captured so far, in emission order. */
  def events: Chunk[WorkflowEvent] < Async = reporter.events

  /** Execute a goal. STUBBED — implementation lands in Phase 11. */
  def run[A](goal: Task[A]): A < (Async & Abort[WorkflowError]) =
    Abort.fail(WorkflowError.TaskFailed(
      goal.id,
      "WorkflowTestDriver.run not yet wired — Workflow.run lands in plan Phase 11 (Task 44+)",
    ))

end WorkflowTestDriver

object WorkflowTestDriver:

  /** Constructs a fresh driver wired with an [[InMemoryCacheStore]] and a
    * fresh [[TestReporter]].
    */
  def init(using Frame): WorkflowTestDriver < (Async & Abort[StoreError]) =
    for
      store    <- InMemoryCacheStore.init
      reporter <- TestReporter.init
    yield new WorkflowTestDriver(store, reporter)

end WorkflowTestDriver
