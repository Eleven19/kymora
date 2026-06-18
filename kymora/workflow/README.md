# kymora-workflow

`kymora-workflow` is a Kyo-native DAG task engine with Mill-aligned incremental
caching semantics. A workflow is built from typed `Task[A]` nodes, executed
through the `Workflow` effect, and backed by a `Workflow.Runtime` that supplies
VFS access, cache layout, observability, and run configuration.

The module is published for JVM, Scala.js, Scala.js WASM, and Scala Native.
WASM tests run on Node.js 24+.

See [the design spec](../../docs/superpowers/specs/2026-06-16-kymora-workflow-design.md)
for the architecture and conventions.

## Overview

- `Task[A]` — sealed trait. Variants: `Task.Cached`, `Task.Persistent`,
  `Task.Activity`, `Task.Source`, `Task.Sources`, `Task.Input`, `Task.Command`.
  All built
  via `Task.<kind>` smart constructors:
  - `Task.cached` (canonical) / `Task.init` (alias) — cached
  - `Task.persistent` — persisted-output
  - `Task.activity` — graph-internal work that always runs and never stores
    its own output
  - `Task.source` / `Task.sourceQuick` — single-path file input
  - `Task.sources` / `Task.sourcesQuick` — multi-path (Mill `Sources`
    analogue); produces an ordered `Chunk[VPathRef]` with an
    order-sensitive aggregate fingerprint
  - `Task.input` — pure-value input
  - `Task.command` — always-runs command, intended for CLI/tooling edges
- `AnyTask` — top-level opaque wrapper for heterogeneous internal collections
  of tasks without exposing `Task[?]` as the public ergonomic story.
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
  - `Workflow.context` / `Workflow.dest` expose the task context while a task
    value is being evaluated.
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

Task values can access their engine-managed destination directory through
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
after success. A later cache hit decodes the stored value and does not evaluate
the task value again. Persistent tasks run directly in `.dest`, so state can
survive invalidating invocations:

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

## Task Kinds

Each task kind is useful in a different part of the graph. These examples all
wire dependencies so the cache behavior is visible.

### Source

Use a single path as a content-hashed file input:

```scala
val source = Task.source("source")(VPath.root / "src" / "Main.scala")
val compile = Task.cached("compile")(source) { ref =>
  s"compiled ${ref.path.show} at ${ref.fingerprint.value}"
}
```

### Sources

Use ordered multi-path inputs when reordering is meaningful:

```scala
val sourceFiles =
  Task.sources("sources")(
    VPath.root / "src" / "Main.scala",
    VPath.root / "src" / "Util.scala",
  )

val digest = Task.cached("digest")(sourceFiles) { refs =>
  refs.map(_.fingerprint.value).mkString("\n")
}
```

### Input

Use `Task.input` for non-file values that should influence downstream cache
keys:

```scala
var scalaVersion = "3.8.4"
val version = Task.input("scalaVersion")(scalaVersion)
val report = Task.cached("version-report")(version) { v =>
  s"compiled with Scala $v"
}
```

### Cached

`Task.cached` stores a typed `TaskRecord[A]`. A valid hit decodes the stored
value and skips evaluation:

```scala
final case class Report(name: String, total: Int) derives Schema

val count = new java.util.concurrent.atomic.AtomicInteger(0)
val report = Task.cached("report") {
  Report("run", count.incrementAndGet())
}
```

### Persistent

`Task.persistent` has the same typed record semantics as `Task.cached`, but it
evaluates in a preserved `.dest` directory when invalidated:

```scala
var revision = 1
val input = Task.input("revision")(revision)

val stateful = Task.persistent("stateful")(input) { _ =>
  for
    dest <- Workflow.dest
    marker = dest / "marker.txt"
    vfs <- Vfs.get
    exists <- vfs.exists(marker)
    value <- if exists then marker.read else marker.write("first").map(_ => "first")
  yield value
}
```

### Activity

`Task.activity` is non-cached graph-internal work. It evaluates once per
workflow execution, never writes a record, and still hashes its value for cached
dependents:

```scala
val clock = Task.activity("clock")(System.currentTimeMillis())
val formatted = Task.cached("formatted-clock")(clock) { millis =>
  s"clock=$millis"
}
```

### Command

`Task.command` is for CLI/tooling entrypoints. Commands always run when selected
as goals; their dependencies still cache normally:

```scala
val packageJar = Task.cached("package")(source) { ref =>
  s"jar for ${ref.path.show}"
}

val publish = Task.command("publish")(packageJar) { jar =>
  Console.printLine(s"publishing $jar").map(_ => jar)
}
```

Prefer `Task.activity` for ordinary graph-internal non-cached work. Use
`Task.command` at the edge where a tool or CLI action is the selected goal.

## Cache Typeclasses

`Task.cached`, `Task.init`, and `Task.persistent` require `Cacheable[A]` and
`Hashable[A]` for their output type. For most values, deriving `Schema` is
enough because workflow provides Schema-backed defaults:

```scala
final case class Report(name: String, total: Int) derives Schema

val report = Task.cached("report") {
  Report("run", 1)
}
```

You can also derive `Cacheable` explicitly:

```scala
final case class ExplicitReport(name: String) derives Schema, Cacheable
```

Define a manual `Cacheable[A]` if the encoded cache value needs a custom schema
or migration strategy, and define a custom `Hashable[A]` when downstream
invalidation should ignore or normalize part of the value:

```scala
final case class Seed(stable: Int, volatile: Int) derives Schema

given Hashable[Seed] =
  seed => summon[Hashable[Int]].hash(seed.stable)
```

Use `TaskVersion` to intentionally invalidate a task value:

```scala
val bundle = Task.cached("bundle", TaskVersion(2, 0, 0)) {
  "new bundle format"
}
```

## Errors And Observability

Workflow execution fails through `Abort[WorkflowError]`. Task values may fail
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

Behavior requirements are documented in
[`docs/behavior.ears.md`](docs/behavior.ears.md). Requirement families map to
the workflow test suites named in that file.

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
`pt.kcry::blake3` — cross-platform across JVM, Scala.js, Scala.js WASM, and
Scala Native.
Hashes are byte-identical on every platform, so cache manifests written on
one platform are valid on any other.
