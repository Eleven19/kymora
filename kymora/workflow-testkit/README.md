# kymora-workflow-testkit

Test helpers for [`kymora-workflow`](../workflow). Published to downstream
users for JVM, Scala.js, Scala.js WASM, and Scala Native. WASM tests run on
Node.js 24+.

## Why a separate testkit module

Helpers shipped here are real API — downstream users testing their own
workflows need the same fakes we use internally. Keeping them as private
test code would force every consumer to reinvent them.

## What's here

- `WorkflowSpec` — `kyo.test.Test[Any]` base class that adds a `test`
  helper applying a default per-test timeout (3 min). Drop a wedged test
  with a `[TIMEOUT]` marker instead of hanging the JVM.
- `WorkflowTestDriver.init` — single-call harness composing the testkit
  primitives.
- `InMemoryCacheStore.init` — `VfsDirStore` over an in-memory VFS.
- `TestClock` — controllable `Clock` fake (`set` / `advance`).
- `CollectingObserver` — an `Observer` that captures the `WorkflowEvent`
  stream for assertions.
- `TaskBuilder` — ObjectMothers for common graph shapes (`linearChain`,
  `diamond`, `sourceInputChain`).
- `TestVfs.tempDir` — `Scope`-bound temp VFS.

## Usage sketch

```scala doctest:expect=skipped
import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

class MyEngineSpec extends WorkflowSpec:
  test("goal runs end-to-end") {
    for
      driver <- WorkflowTestDriver.init
      result <- driver.run(myGoal)
      events <- driver.events
    yield assert(result == expected)
  }

  // Raw kyo-test syntax is still available alongside `test(...)`:
  "no auto-timeout on raw `in`" in { ... }
  "explicit per-test timeout".timeout(10.seconds) in { ... }
```

## Gotchas

- **`kyo.*` shadows workflow names under wildcard imports.** Construct
  tasks via `Task.<kind>` (`Task.command`, `Task.source`, `Task.input`, …)
  to dodge the `kyo.Command` shadow — see the workflow module README.
