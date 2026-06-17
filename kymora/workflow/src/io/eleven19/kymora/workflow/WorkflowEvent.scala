package io.eleven19.kymora.workflow

import kyo.*

/** Structured event emitted by the engine for observability.
  *
  * NOTE: minimal cases here. The full ADT (`RunStarted`, `RunCompleted`,
  * `TaskStarted`, `TaskCached`, `TaskCompleted`, `TaskFailed`,
  * `TaskCancelled`, ...) lands in Phase 10 (Task 38). This forward
  * declaration exists so the testkit can capture and assert against the
  * event stream before the engine is wired up.
  */
sealed trait WorkflowEvent derives CanEqual

object WorkflowEvent:
  /** A task has been queued by the scheduler. */
  final case class TaskQueued(id: TaskId) extends WorkflowEvent
  // More cases come in Task 38.
end WorkflowEvent
