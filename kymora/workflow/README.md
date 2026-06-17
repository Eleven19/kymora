# kymora-workflow

Kyo-native DAG task-graph engine with Mill-aligned incremental caching.

See [the design spec](../../docs/superpowers/specs/2026-06-16-kymora-workflow-design.md)
for the architecture and conventions.

## Key concepts

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
- `Workflow.Services` — aggregate type alias for the four `Env` effects
  the engine reads:
  `Env[Workflow.Config] & Env[Vfs] & Env[CacheStore] & Env[Observer]`.
  - `Workflow.Config` carries pure run-level configuration (codec,
    parallelism, flags). Service-like dependencies (`Vfs`, `CacheStore`,
    `Observer`) are NOT on Config — they're separate Env effects so test
    harnesses can swap them independently.
  - `Workflow.Services.Bundle` + `Services.init` / `Services.default` /
    `Services.layer.{config,vfs,store,observer}` / `Services.provide`
    are the construction helpers.
- `Workflow.run(goal)` / `runAll(goals*)` — engine entry points;
  signatures take `Async & Workflow.Services & Abort[WorkflowError]`.
- `io.eleven19.kymora.workflow.cli.Cli.runWith(task, tokens)` — bridges
  [kyo-case-app](https://github.com/getkyo/kyo) argument parsing to
  parameterized commands. Provide a case class with `caseapp.Parser` /
  `caseapp.Help` instances in scope, build the command via
  `Task.command[A, Args]("name") { args => ... }`, then run with
  `Cli.runWith(cmd, args)` under the ambient `Workflow.Services`.
- `CacheStore` — pluggable persistence. Default: `VfsDirStore` backed by
  `kymora-vfs`.
- `WorkflowEvent` + `Observer` — observability stream. The default
  `ConsoleObserver` / `JsonLinesObserver` write through Kyo's `Console`
  effect (not `System.out`) so a test runner can capture output.

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
