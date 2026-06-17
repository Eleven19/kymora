package io.eleven19.kymora.examples.agentskills

import io.eleven19.kymora.workflow.*
import kyo.*

// `kyo.*` re-exports its own `Command` type which would shadow the package-
// level workflow `Command` alias under the wildcard import. Pin our alias
// back so the `summariseCmd` / `compareCmd` signatures resolve to the
// workflow Command type.
import io.eleven19.kymora.workflow.Command

/** Sample agent skills wired on top of the kymora-workflow engine.
  *
  * Demonstrates the same primitives that power the build-style example
  * ([[io.eleven19.kymora.examples.smilebuild]]) in a non-build use case:
  *
  *   - "Loading" a knowledge-base document is modelled as an [[Input]] — a
  *     pure value that is re-read on every invocation but whose value-hash
  *     is folded into the dependent skill's cache key.
  *   - A `summarise` skill is a [[Task]] depending on one document.
  *   - A `compare` skill is a [[Task]] depending on two documents.
  *   - CLI entry points (`summariseCmd`, `compareCmd`) wrap the cached
  *     skills as [[Command]]s so an agent harness can invoke them through
  *     `Workflow.runCli`.
  *
  * Skill bodies are intentionally stubbed (string concatenation, no LLM
  * call) — the point is the wiring shape, not real summarisation.
  */
object Skills:
  // Anchor every id under the "agent" scope.
  import SkillScope.default

  /** Synthetic knowledge-base doc A. The body is a constant string; a real
    * implementation would pull from a vector store, working copy, or HTTP
    * endpoint.
    */
  val loadDocA: Input[String] = Input.init("loadDocA")("doc-a contents")

  /** Synthetic knowledge-base doc B. */
  val loadDocB: Input[String] = Input.init("loadDocB")("doc-b contents")

  /** Summarise a single document. Cached over `(id, version, doc-hash)` — a
    * re-run with the same doc body skips the body entirely.
    */
  val summarise: Skill[String] = Task.init("summarise")(loadDocA) { doc =>
    s"summary of: $doc"
  }

  /** Compare two documents. Cached over the joint fingerprint of both
    * inputs.
    */
  val compare: Skill[String] = Task.init("compare")(loadDocA, loadDocB) { (a, b) =>
    s"compare ${a.length} chars vs ${b.length} chars"
  }

  /** CLI entry: invokes the cached `summarise` skill and returns its result.
    *
    * Wired as a [[Command]] so the surrounding agent harness can call it
    * through `Workflow.runCli` without the skill itself losing memoisation.
    */
  val summariseCmd: Command[String] = Command.init("summariseCmd")(summarise) { s => s }

  /** CLI entry: invokes the cached `compare` skill and returns its result. */
  val compareCmd: Command[String] = Command.init("compareCmd")(compare) { s => s }
end Skills
