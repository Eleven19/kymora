package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.internal.Validation
import kyo.*

opaque type TaskId = String

object TaskId:
  inline def apply(inline literal: String): TaskId =
    ${ io.eleven19.kymora.workflow.macros.TaskIdMacros.literal('literal) }

  def parse(s: String): Result[Validation.Reason, TaskId] = Validation.check(s)
  def unsafe(s: String): TaskId                           = s

  extension (id: TaskId)
    def value: String          = id
    def segments: List[String] = id.split('.').toList.filter(_.nonEmpty)

  given CanEqual[TaskId, TaskId] = CanEqual.derived

  given schema: Schema[TaskId] =
    Schema.stringSchema.transform[TaskId](TaskId.unsafe)(_.value)
end TaskId
