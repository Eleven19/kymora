package io.eleven19.kymora.workflow

import kyo.*

/** Errors raised by the workflow engine — both during graph construction and
  * task execution.
  *
  * The hierarchy is closed (`sealed trait`) and `CanEqual`-derived for strict
  * equality. `Schema` is derived for round-trip serialization (cache +
  * observability persistence). Every `TaskId`/`TaskScope` field is backed by an
  * explicit `given Schema[…]` in the corresponding companion object, so the
  * public error surface keeps stable string encodings.
  *
  * Several variants carry a `Chunk` of items where a non-empty list is the
  * intended invariant (e.g. `DuplicateTaskId.kinds`, `CycleDetected.cycle`,
  * `Partial.errors`). The non-empty contract is documented per field rather
  * than encoded in the type; constructors that produce these errors must enforce
  * it.
  */
sealed trait WorkflowError derives CanEqual, Schema
object WorkflowError:
  // Graph-construction (pre-execution) -------------------------------------

  /** A `TaskId` was registered by more than one task kind in the same scope.
    *
    * @param kinds
    *   The colliding task kinds (e.g. `"Task.Cached"`, `"Task.Persistent"`).
    *   Invariant: non-empty.
    */
  final case class DuplicateTaskId(id: TaskId, kinds: Chunk[String]) extends WorkflowError

  /** A raw string failed `TaskId` validation. */
  final case class InvalidTaskId(raw: String, reason: String) extends WorkflowError

  /** The dependency graph contains a cycle.
    *
    * @param cycle
    *   The cycle, in traversal order (the last node depends on the first).
    *   Invariant: non-empty.
    */
  final case class CycleDetected(cycle: Chunk[TaskId]) extends WorkflowError

  // Per-task execution -----------------------------------------------------

  /** A task threw or returned a failure. `Throwable` is reduced to its message
    * so the error remains `Schema`-derivable for cache + observability.
    */
  final case class TaskFailed(id: TaskId, message: String)    extends WorkflowError
  final case class TaskCancelled(id: TaskId, reason: String)  extends WorkflowError

  /** Wraps a storage-layer failure so the engine surface remains a single ADT. */
  final case class Store(cause: StoreError) extends WorkflowError

  /** Aggregated errors from `continueOnError` execution mode.
    *
    * @param errors
    *   The collected per-task failures. Invariant: non-empty.
    */
  final case class Partial(errors: Chunk[WorkflowError]) extends WorkflowError
end WorkflowError
