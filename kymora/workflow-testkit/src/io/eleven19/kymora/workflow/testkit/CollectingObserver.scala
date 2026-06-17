package io.eleven19.kymora.workflow.testkit

import io.eleven19.kymora.workflow.*
import kyo.*

/** An [[Observer]] that captures emitted [[WorkflowEvent]]s into an in-memory
  * buffer for test assertions.
  *
  * Uses an [[AtomicRef]] so concurrent `onEvent` calls (the engine may run
  * tasks in parallel and emit events from multiple fibers) are safely
  * recorded.
  *
  * Construct via [[CollectingObserver.init]] and inspect the buffer with
  * [[events]] or one of the convenience filters such as [[queued]].
  */
final class CollectingObserver private (
    private val ref: AtomicRef[Chunk[WorkflowEvent]]
) extends Observer:

  /** Append `event` to the captured buffer. */
  override def onEvent(event: WorkflowEvent): Unit < Async =
    ref.updateAndGet(_.appended(event)).unit

  /** All captured events, in the order they were emitted. */
  def events: Chunk[WorkflowEvent] < Async = ref.get

  /** Ids of every [[WorkflowEvent.TaskQueued]] event captured so far. */
  def queued: Chunk[TaskId] < Async =
    events.map(_.collect { case WorkflowEvent.TaskQueued(id) => id })

end CollectingObserver

object CollectingObserver:

  /** Constructs a fresh [[CollectingObserver]] with an empty event buffer. */
  def init(using Frame): CollectingObserver < Async =
    AtomicRef.init(Chunk.empty[WorkflowEvent]).map(ref => new CollectingObserver(ref))

end CollectingObserver
