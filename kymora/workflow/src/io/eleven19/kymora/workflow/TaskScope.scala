package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.internal.Validation
import kyo.*

/** Ambient definition scope used to prefix task ids.
  *
  * Scopes let libraries define local task names while callers compose them into
  * larger graphs. `Workflow.scope("module") { ... }` installs a scope for the
  * tasks defined in its body.
  */
opaque type TaskScope = String

object TaskScope:
  /** Empty root scope. */
  val Root: TaskScope            = ""
  /** Default ambient scope used when no explicit scope is installed. */
  given default: TaskScope       = Root

  /** Compile-time checked scope literal. */
  inline def apply(inline literal: String): TaskScope =
    ${ io.eleven19.kymora.workflow.macros.TaskScopeMacros.literal('literal) }

  /** Runtime validation for user-provided scopes. */
  def parse(s: String): Result[Validation.Reason, TaskScope] = Validation.check(s)
  /** Wraps a scope without validation; intended for internals and trusted tests. */
  def unsafe(s: String): TaskScope                           = s

  extension (scope: TaskScope)
    /** Raw dot-separated scope value. */
    def value: String    = scope
    /** Whether this is [[Root]]. */
    def isEmpty: Boolean = scope.isEmpty
    /** Prefixes a task id suffix with this scope. */
    def qualify(suffix: String): TaskScope =
      if scope.isEmpty then suffix else s"$scope.$suffix"
    /** Dot-separated scope segments. */
    def segments: List[String] = scope.split('.').toList.filter(_.nonEmpty)

  given CanEqual[TaskScope, TaskScope] = CanEqual.derived

  given schema: Schema[TaskScope] =
    Schema.stringSchema.transform[TaskScope](TaskScope.unsafe)(_.value)
end TaskScope
