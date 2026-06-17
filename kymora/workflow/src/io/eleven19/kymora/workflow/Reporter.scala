package io.eleven19.kymora.workflow

import kyo.*

/** Consumer of the engine's [[WorkflowEvent]] stream.
  *
  * NOTE: minimal surface here. The full session API (`openSession`,
  * `Session.close`, batched flushes, etc.) lands in Phase 10 (Task 39).
  * For now, a `Reporter` only exposes a single `onEvent` callback that the
  * engine calls per emitted event.
  */
trait Reporter:
  /** Called by the engine for each emitted [[WorkflowEvent]]. */
  def onEvent(event: WorkflowEvent): Unit < Async
end Reporter
