package io.eleven19.kymora.workflow

import kyo.*

/** Structured event emitted by the engine for observability.
  *
  * Schema derivation is intentionally NOT attached to the sealed trait. If a
  * downstream consumer needs a `Schema[WorkflowEvent]`, provide an explicit
  * event schema at that boundary so opaque identifiers and fingerprints keep
  * their stable encodings.
  */
sealed trait WorkflowEvent derives CanEqual

object WorkflowEvent:
  /** A workflow run has started with the given goal task IDs. */
  final case class RunStarted(goals: Chunk[TaskId], at: java.time.Instant) extends WorkflowEvent

  /** A workflow run has completed with aggregate cache statistics. */
  final case class RunCompleted(durationMs: Long, hits: Int, misses: Int, failed: Int) extends WorkflowEvent

  /** A task has been queued by the scheduler. */
  final case class TaskQueued(id: TaskId) extends WorkflowEvent

  /** A task has started executing with the given resolved dependencies. */
  final case class TaskStarted(id: TaskId, deps: Chunk[TaskId], at: java.time.Instant) extends WorkflowEvent

  /** A task was satisfied from cache with the given inputs fingerprint. */
  final case class TaskCached(id: TaskId, inputsHash: Fingerprint) extends WorkflowEvent

  /** A task ran successfully, producing a value with the given fingerprint. */
  final case class TaskCompleted(id: TaskId, valueHash: Fingerprint, durationMs: Long) extends WorkflowEvent

  /** A task failed with the given error message. */
  final case class TaskFailed(id: TaskId, message: String) extends WorkflowEvent

  /** A task was cancelled before completion. */
  final case class TaskCancelled(id: TaskId, reason: String) extends WorkflowEvent
end WorkflowEvent
