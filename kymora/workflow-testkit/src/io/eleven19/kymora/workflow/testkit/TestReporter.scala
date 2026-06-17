package io.eleven19.kymora.workflow.testkit

import io.eleven19.kymora.workflow.*
import kyo.*

/** Captures emitted [[WorkflowEvent]]s into an in-memory buffer for test
  * assertions.
  *
  * Uses an [[AtomicRef]] so concurrent `onEvent` calls (the engine may run
  * tasks in parallel and emit events from multiple fibers) are safely
  * recorded.
  *
  * Construct via [[TestReporter.init]] and inspect the buffer with
  * [[events]] or one of the convenience filters such as [[queued]].
  */
final class TestReporter private (
    private val ref: AtomicRef[Chunk[WorkflowEvent]]
) extends Reporter:

  /** Append `event` to the captured buffer. */
  override def onEvent(event: WorkflowEvent): Unit < Async =
    ref.updateAndGet(_.appended(event)).unit

  /** All captured events, in the order they were emitted. */
  def events: Chunk[WorkflowEvent] < Async = ref.get

  /** Ids of every [[WorkflowEvent.TaskQueued]] event captured so far. */
  def queued: Chunk[TaskId] < Async =
    events.map(_.collect { case WorkflowEvent.TaskQueued(id) => id })

end TestReporter

object TestReporter:

  /** Constructs a fresh [[TestReporter]] with an empty event buffer. */
  def init(using Frame): TestReporter < Async =
    AtomicRef.init(Chunk.empty[WorkflowEvent]).map(ref => new TestReporter(ref))

end TestReporter
