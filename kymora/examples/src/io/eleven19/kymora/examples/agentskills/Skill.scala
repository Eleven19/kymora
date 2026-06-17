package io.eleven19.kymora.examples.agentskills

import io.eleven19.kymora.workflow.*

/** Alias for tasks treated as agent "skills".
  *
  * A skill is just a [[Task]] — the engine is non-build agnostic. We use a
  * type alias to mark intent at the call site: this Task is invocable as a
  * skill in an agent harness, not as part of a build graph.
  */
type Skill[A] = Task[A]

/** Convention helper: scope skills under the `"agent"` prefix.
  *
  * Importing `SkillScope.default` brings a `given TaskScope` into scope so
  * that every `Task.init` / `Task.command` / `Task.input` invocation lands
  * under `agent.<id>` without per-call wiring.
  */
object SkillScope:
  given default: TaskScope = TaskScope("agent")
end SkillScope
