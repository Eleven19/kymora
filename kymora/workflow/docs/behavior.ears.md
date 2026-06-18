# Workflow Behavior Requirements

This document is the normative behavior checklist for `kymora-workflow`.
Requirement names are mirrored by test names or suite sections so behavior can
be traced from docs to executable coverage.

## Task Kinds

- **WF-SOURCE-001**: When a `Task.source` is evaluated, the engine shall read
  the referenced path through the runtime VFS and produce a `VPathRef` whose
  fingerprint contributes to dependent cache keys.
- **WF-SOURCES-001**: When a `Task.sources` is evaluated, the engine shall
  preserve declaration order and produce an order-sensitive aggregate
  fingerprint for dependents.
- **WF-INPUT-001**: When a `Task.input` is evaluated, the engine shall evaluate
  the input value once per workflow execution and hash it with `Hashable[A]`.
- **WF-CACHED-001**: When a `Task.cached` has a valid cache record, the engine
  shall decode and return the stored value without evaluating the task value.
- **WF-CACHED-002**: When a `Task.cached` has no valid cache record, the engine
  shall evaluate the value, seal `.dest.tmp` into `.dest`, and write a typed
  `TaskRecord[A]`.
- **WF-PERSISTENT-001**: When a `Task.persistent` has a valid cache record, the
  engine shall decode and return the stored value without evaluating the task
  value.
- **WF-PERSISTENT-002**: When a `Task.persistent` is invalidated, the engine
  shall evaluate in the preserved `.dest` directory.
- **WF-ACTIVITY-001**: When a `Task.activity` is required, the engine shall
  evaluate it at most once per workflow execution and shall not read or write a
  cache record for it.
- **WF-ACTIVITY-002**: When a cached dependent observes a changed activity
  `valueHash`, the dependent shall be invalidated.
- **WF-COMMAND-001**: When a `Task.command` is selected as a goal, the engine
  shall evaluate it at most once per workflow execution and shall not store its
  own output.

## Graph Execution

- **WF-GRAPH-001**: When a workflow is planned, dependencies shall be scheduled
  before dependents.
- **WF-GRAPH-002**: When multiple reachable tasks have the same `TaskId`, the
  planner shall fail with `WorkflowError.DuplicateTaskId`.
- **WF-GRAPH-003**: When `Workflow.runAll` executes goals, returned values shall
  preserve caller goal order.
- **WF-GRAPH-004**: When a task is needed by multiple dependents in one
  execution, the engine shall reuse the in-run memo and shall not evaluate or
  decode the same task more than once.
- **WF-GRAPH-005**: When a task fails, public workflow execution shall fail
  through `Abort[WorkflowError]`.

## Cache Records

- **WF-CACHE-001**: When a record has matching schema version, task version,
  body hash, inputs hash, and value hash, the engine shall treat it as a hit.
- **WF-CACHE-002**: When task version, body hash, inputs hash, dependency hash,
  source hash, or parameter hash changes, the engine shall treat the record as
  a miss and refresh it unless writes are disabled.
- **WF-CACHE-003**: When record decoding fails, the engine shall fail with
  `WorkflowError.Store`.
- **WF-CACHE-004**: When a record's stored value hash does not match the
  decoded value, the engine shall fail with `WorkflowError.Store`.
- **WF-CACHE-005**: When `Workflow.Config.noCache` is true, the engine shall
  skip cache reads and writes.
- **WF-CACHE-006**: When `Workflow.Config.bypass` contains a task id, the
  engine shall skip reads for that task and write a refreshed record.
- **WF-CACHE-007**: When `Workflow.Config.readOnly` is true, the engine shall
  read existing records but shall not write refreshed records.
- **WF-CACHE-008**: When a cacheable output has a `Schema[A]`, `Cacheable[A]`
  and `Hashable[A]` shall be available through default derivation unless a
  custom instance is supplied.
- **WF-CACHE-009**: When a custom `Hashable[A]` is supplied, downstream
  invalidation shall follow that hash rather than the default schema hash.

## Traceability

- `SourceTests`, `SourceInvalidationTests`, and `SourcesInvalidationTests`
  cover WF-SOURCE and WF-SOURCES requirements.
- `InputTests` covers WF-INPUT-001.
- `EngineTests`, `WorkflowEffectTests`, `TypedCachingBehaviorTests`, and
  `PersistentEngineTests` cover WF-CACHED, WF-PERSISTENT, WF-CACHE, and
  WF-GRAPH memoization requirements.
- `ActivityTests` covers WF-ACTIVITY requirements.
- `CommandEngineTests` and `CliTests` cover WF-COMMAND requirements.
- `GraphTests`, `ParallelismTests`, and `WorkflowEffectTests` cover graph
  ordering, duplicate ids, runAll ordering, and failure propagation.
