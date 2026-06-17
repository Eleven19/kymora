# kymora-workflow

Kyo-native DAG task-graph engine with Mill-aligned incremental caching.

See [the design spec](../../docs/superpowers/specs/2026-06-16-kymora-workflow-design.md)
for the architecture and conventions.

## Key concepts

- `Task[A]` — sealed trait. Variants: `Task.Cached`, `Task.Persistent`,
  `Task.Source`, `Task.Input`, `Task.Command`. All built via
  `Task.<kind>` smart constructors:
  - `Task.init` — cached
  - `Task.persistent` — persisted-output
  - `Task.source` / `Task.sourceQuick` — file/dir input
  - `Task.input` — pure-value input
  - `Task.command` / `Task.cli[Args]` — always-runs command
- `Workflow.scope(prefix)` — definition-scope helper (compile-time validated).
- `Workflow.Config` — runtime config injected via `Env[Workflow.Config]`.
- `Workflow.run(goal)` / `runAll` / `runCli` — engine entry points.
- `CacheStore` — pluggable persistence. Default: `VfsDirStore` backed by
  `kymora-vfs`.
- `WorkflowEvent` + `Observer` — observability stream.

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
  through `Task.init`, `Task.persistent`, `Task.source`, `Task.input`,
  `Task.command`, or `Task.cli[Args]`. This sidesteps the `kyo.Command`
  shadow that `import kyo.*` introduces.
