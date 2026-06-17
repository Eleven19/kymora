package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.internal.Validation
import kyo.*

opaque type TaskScope = String

object TaskScope:
  val Root: TaskScope            = ""
  given default: TaskScope       = Root

  inline def apply(inline literal: String): TaskScope =
    ${ io.eleven19.kymora.workflow.macros.TaskScopeMacros.literal('literal) }

  def parse(s: String): Result[Validation.Reason, TaskScope] = Validation.check(s)
  def unsafe(s: String): TaskScope                           = s

  extension (scope: TaskScope)
    def value: String    = scope
    def isEmpty: Boolean = scope.isEmpty
    def qualify(suffix: String): TaskScope =
      if scope.isEmpty then suffix else s"$scope.$suffix"
    def segments: List[String] = scope.split('.').toList.filter(_.nonEmpty)

  given CanEqual[TaskScope, TaskScope] = CanEqual.derived
end TaskScope
