package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.internal.Validation
import kyo.*

/** Stable identifier for a task in a workflow graph.
  *
  * Task IDs are dot-separated and validated at compile time when constructed from string literals with
  * [[TaskId.apply]]. Use [[TaskId.parse]] for dynamic input.
  */
opaque type TaskId = String

object TaskId:

    /** Compile-time checked task id literal. */
    inline def apply(inline literal: String): TaskId =
        ${ io.eleven19.kymora.workflow.macros.TaskIdMacros.literal('literal) }

    /** Runtime validation for user-provided task ids. */
    def parse(s: String): Result[Validation.Reason, TaskId] = Validation.check(s)

    /** Wraps an id without validation; intended for internals and trusted tests. */
    def unsafe(s: String): TaskId = s

    extension (id: TaskId)
        /** Raw dot-separated id value. */
        def value: String = id

        /** Dot-separated id segments. */
        def segments: List[String] = id.split('.').toList.filter(_.nonEmpty)

    given CanEqual[TaskId, TaskId] = CanEqual.derived

    given schema: Schema[TaskId] =
        Schema.stringSchema.transform[TaskId](TaskId.unsafe)(_.value)
end TaskId
