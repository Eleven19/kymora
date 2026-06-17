package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.internal.Validation
import kyo.*

object Workflow:
  /** Definition-scope helper.
    *
    * Compile-time validated literal prefix. Threads a using TaskScope so all
    * Task.init / Source.init / Input.init / Command.init invocations inside
    * `body` see the qualified prefix.
    *
    * Resource scopes (cache lock, reporter session, etc.) use Kyo's Scope
    * effect separately — see Workflow.run in Phase 11.
    */
  inline def scope[A](inline prefix: String)(body: TaskScope ?=> A)(using
      outer: TaskScope,
  ): A =
    body(using outer.qualify(TaskScope(prefix).value))

  /** Runtime-parsed prefix variant. Returns Result.fail on invalid input. */
  def scopeWith[A](prefix: String)(body: TaskScope ?=> A)(using
      outer: TaskScope,
  ): Result[Validation.Reason, A] =
    TaskScope.parse(prefix).map(s => body(using outer.qualify(s.value)))
end Workflow
