# kymora-workflow

Kyo-native DAG task-graph engine with Mill-aligned incremental caching.

See [the design spec](../../docs/superpowers/specs/2026-06-16-kymora-workflow-design.md)
for the architecture and conventions.

## Key concepts

- `Task[A]` — sealed trait. Variants: `Task.Cached`, `Task.Persistent`,
  `Task.Source`, `Task.Input`, `Task.Command`.
- `Workflow.scope(prefix)` — definition-scope helper (compile-time validated).
- `Workflow.Config` — runtime config injected via `Env[Workflow.Config]`.
- `Workflow.run(goal)` / `runAll` / `runCli` — engine entry points.
- `CacheStore` — pluggable persistence. Default: `VfsDirStore` backed by
  `kymora-vfs`.
- `WorkflowEvent` + `Reporter` — observability stream.

## Examples

See [`kymora-examples`](../examples) for:

- [`smile-build`](../examples/src/io/eleven19/kymora/examples/smilebuild) —
  Mill-like build DSL.
- [`agent-skills`](../examples/src/io/eleven19/kymora/examples/agentskills) —
  workflow-backed agent skills.

## Testing

See [`kymora-workflow-testkit`](../workflow-testkit) for `WorkflowTestDriver`,
`TestClock`, `TestReporter`, `InMemoryCacheStore`, and `TaskBuilder`
ObjectMothers.
