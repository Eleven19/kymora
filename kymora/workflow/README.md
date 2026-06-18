# kymora-workflow

`kymora-workflow` is a Kyo-native DAG task engine with Mill-aligned incremental
caching semantics. A workflow is built from typed `Task[A]` nodes, executed
through the `Workflow` effect, and backed by a `Workflow.Runtime` that supplies
VFS access, cache layout, observability, and run configuration.

See [the design spec](../../docs/superpowers/specs/2026-06-16-kymora-workflow-design.md)
for the architecture and conventions.

## Overview

- `Task[A]` — sealed trait. Variants: `Task.Cached`, `Task.Persistent`,
  `Task.Source`, `Task.Sources`, `Task.Input`, `Task.Command`. All built
  via `Task.<kind>` smart constructors:
  - `Task.cached` (canonical) / `Task.init` (alias) — cached
  - `Task.persistent` — persisted-output
  - `Task.source` / `Task.sourceQuick` — single-path file input
  - `Task.sources` / `Task.sourcesQuick` — multi-path (Mill `Sources`
    analogue); produces an ordered `Chunk[VPathRef]` with an
    order-sensitive aggregate fingerprint
  - `Task.input` — pure-value input
  - `Task.command` — always-runs command
- **Parameterized variants:** `Task.cached[A, P]`, `Task.persistent[A, P]`,
  and `Task.command[A, P]` return a `P => Task[A]` (or `P => Command[A]`).
  For Cached/Persistent, `P` participates in the cache key via
  `Hashable[P]` — different `P` values produce different cache entries
  against the same `TaskId`. Commands carry no `paramHash` (they never
  cache).
- `Workflow.scope(prefix)` — definition-scope helper (compile-time validated).
- `Workflow` — first-class Kyo effect for task execution. Handle it with
  `Workflow.handle(runtime)(...)`.
  - `Workflow.Runtime(vfs)` carries execution dependencies: writable VFS
    backend plus default run config, cache root, observer, and value codec.
    Override those defaults with named arguments when needed.
  - `Workflow.Runtime.default` creates an in-memory runtime using the same
    defaults.
  - `Workflow.context` / `Workflow.dest` expose the task context inside task
    bodies.
- `Workflow.run(goal)` / `runAll(goals*)` — engine entry points;
  signatures take `Async & Workflow & Abort[WorkflowError]`. `task()` is
  shorthand for `Workflow.run(task)`.
- `io.eleven19.kymora.workflow.cli.Cli.runWith(task, tokens)` — bridges
  [kyo-case-app](https://github.com/getkyo/kyo) argument parsing to
  parameterized commands. Provide a case class with `caseapp.Parser` /
  `caseapp.Help` instances in scope, build the command via
  `Task.command[A, Args]("name") { args => ... }`, then run with
  `Cli.runWith(cmd, args)` under the ambient `Workflow` effect.
- `CacheStore` — cache-store utility API. Workflow execution uses its runtime
  VFS backend and internal cache layout directly; `VfsDirStore` remains
  available for tests and standalone store use.
- `WorkflowEvent` + `Observer` — observability stream. The default
  `ConsoleObserver` / `JsonLinesObserver` write through Kyo's `Console`
  effect (not `System.out`) so a test runner can capture output.

## Basic Usage

```scala
import io.eleven19.kymora.vfs.*
import io.eleven19.kymora.workflow.*
import kyo.*

val compile = Task.cached("compile") {
  "compiled"
}

val program =
  for
    backend <- Vfs.inMemory.init
    runtime = Workflow.Runtime(backend)
    result <- Workflow.handle(runtime) {
                Workflow.run(compile)
              }
  yield result
```

Inside an active `Workflow`, `task()` is shorthand for `Workflow.run(task)`:

```scala
Workflow.handle(runtime) {
  compile()
}
```

## Task Workspaces

Task bodies can access their engine-managed destination directory through
`Workflow.dest` and use normal VFS path syntax:

```scala
val writeReport = Task.cached("report") {
  for
    dest <- Workflow.dest
    file  = dest / "report.txt"
    _    <- file.write("generated report")
  yield file
}
```

Cached tasks run in a temporary `.dest.tmp` workspace and seal it into `.dest`
after success. Persistent tasks run directly in `.dest`, so state can survive
between invocations:

```scala
val stateful = Task.persistent("stateful") {
  for
    dest   <- Workflow.dest
    marker  = dest / "marker.txt"
    vfs    <- Vfs.get
    exists <- vfs.exists(marker)
    value  <- if exists then marker.read else marker.write("first").map(_ => "first")
  yield value
}
```

## Inputs And Cache Keys

`Task.source` and `Task.sources` hash files through the runtime VFS and feed the
result into downstream cache keys. `Task.input` reads arbitrary effectful values
and hashes them through `Hashable[A]`. `Task.cached` and `Task.persistent`
combine task id, version, dependency fingerprints, and parameter hashes to decide
whether work is cached.

```scala
val source = Task.source("source")(VPath.root / "src" / "Main.scala")
val compile = Task.cached("compile")(source) { ref =>
  s"compiled ${ref.path.show} at ${ref.fingerprint.value}"
}
```

Use `TaskVersion` to intentionally invalidate a task body:

```scala
val bundle = Task.cached("bundle", TaskVersion(2, 0, 0)) {
  "new bundle format"
}
```

## Errors And Observability

Workflow execution fails through `Abort[WorkflowError]`. Task bodies may fail
with either ordinary `Throwable`s or `WorkflowError`s; throwables are bridged to
`WorkflowError.TaskFailed`.

```scala
val invalid = Task.cached[Int]("invalid") {
  Abort.fail(WorkflowError.InvalidTaskId("bad id", "contains spaces"))
}

val recovered =
  Workflow.handle(runtime) {
    Abort.run[WorkflowError](invalid())
  }
```

Observers receive structured `WorkflowEvent`s such as `TaskQueued`,
`TaskStarted`, `TaskCached`, `TaskCompleted`, and `TaskFailed`. Use
`Observer.NoOp` for silent runs, `ConsoleObserver` for human-readable output, or
`JsonLinesObserver` for machine-readable event streams.

## Examples

See [`kymora-examples`](../examples) for:

- [`smile-build`](../examples/src/io/eleven19/kymora/examples/smilebuild) —
  Mill-like build DSL.
- [`agent-skills`](../examples/src/io/eleven19/kymora/examples/agentskills) —
  workflow-backed agent skills.

## Testing

See [`kymora-workflow-testkit`](../workflow-testkit) for `WorkflowTestDriver`,
`TestClock`, `CollectingObserver`, `InMemoryCacheStore`, and `TaskBuilder`
ObjectMothers.

## Gotchas

- **Always construct tasks via `Task.<kind>`.** There are no top-level
  `Source` / `Input` / `Command` / `Cmd` aliases — every kind is reached
  through `Task.cached` (or its `Task.init` alias), `Task.persistent`,
  `Task.source`, `Task.input`, or `Task.command`. This sidesteps the
  `kyo.Command` shadow that `import kyo.*` introduces.
- **CLI argument parsing uses kyo-case-app.** The engine no longer ships
  a `Task.cli` constructor or `Workflow.runCli` entry point. Instead,
  build a parameterized command via `Task.command[A, Args]("name") { ... }`
  and invoke it through `io.eleven19.kymora.workflow.cli.Cli.runWith`,
  which threads a `caseapp.Parser[Args]` + `caseapp.Help[Args]` into the
  same `Workflow.run` path.

## Hashing

`Fingerprint.ofBytes` is backed by the pure-Scala BLAKE3 implementation in
`pt.kcry::blake3` — cross-platform across JVM, Scala.js, and Scala Native.
Hashes are byte-identical on every platform, so cache manifests written on
one platform are valid on any other.
