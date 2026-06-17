package io.eleven19.kymora.workflow.testkit

import kyo.*
import kyo.test.*

class WorkflowSpecTests extends WorkflowSpec:

  test("test(name) helper registers a leaf with the default timeout") {
    // Body finishes immediately; we're only proving the helper compiles
    // and registers a leaf the runner picks up.
    assert(true)
  }

  test("defaultTimeout is 3 minutes by default") {
    assert(defaultTimeout == 3.minutes)
  }

  // Mixing styles inside a WorkflowSpec: the raw kyo-test `String in {}`
  // still works (no default timeout applied), and a per-test explicit
  // timeout decorator still wins.
  "raw `in` syntax still works inside WorkflowSpec" in {
    assert(true)
  }

  "per-test timeout override still works inside WorkflowSpec".timeout(30.seconds) in {
    assert(true)
  }

end WorkflowSpecTests

/** Subclass that widens the default timeout. Proves [[WorkflowSpec.defaultTimeout]]
  * is genuinely overridable.
  */
class WorkflowSpecOverrideTests extends WorkflowSpec:
  override def defaultTimeout: Duration = 10.minutes

  test("defaultTimeout can be widened by override") {
    assert(defaultTimeout == 10.minutes)
  }
end WorkflowSpecOverrideTests
