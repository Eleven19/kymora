# kymora-workflow-testkit

Test helpers for [`kymora-workflow`](../workflow). Published to downstream
users.

## Why a separate testkit module

Helpers shipped here are real API — downstream users testing their own
workflows need the same fakes we use internally. Keeping them as private
test code would force every consumer to reinvent them.

## What's here

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

```scala
import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.testkit.*
import kyo.*

for
  driver <- WorkflowTestDriver.init
  result <- Env.run(driver.config)(driver.run(myGoal))
  events <- driver.events
yield (result, events)
```

## Gotchas

- **`kyo.*` shadows workflow names under wildcard imports.** Construct
  tasks via `Task.<kind>` (`Task.command`, `Task.source`, `Task.input`, …)
  to dodge the `kyo.Command` shadow — see the workflow module README.
