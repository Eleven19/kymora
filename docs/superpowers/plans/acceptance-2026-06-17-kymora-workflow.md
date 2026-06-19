# kymora-workflow Acceptance — 2026-06-17

Verification of spec §13 acceptance criteria for the v1 `kymora-workflow`
implementation.

Spec reference: `docs/superpowers/specs/2026-06-16-kymora-workflow-design.md`
(in the `kymora` mainline repo; the impl workspace tracks the plan only).

## Method

Acceptance §13 enumerates nine criteria. Several are implementation-level
behaviors with no dedicated CLI target, so they are verified by the existing
unit-test suites (one suite or set of suites per criterion). The mapping below
names the suite(s) per criterion and the `mill` selector that exercises them.

The full workflow suite is `./mill kymora.workflow.jvm.test`; per-criterion
suites are subsets of that target. Cross-platform coverage is verified by
`./mill kymora.workflow.js.test + kymora.workflow.wasm.test + kymora.workflow.native.test`.

## Results per criterion

| Criterion | Command(s) | Result | Notes |
|---|---|---|---|
| #1 cross-platform compile + jvm tests | `./mill kymora.workflow.jvm.test`, `./mill kymora.workflow.js.test + kymora.workflow.wasm.test + kymora.workflow.native.test` | PASS | 196 / 196 workflow tests passing on JVM, JS, WASM, and Native. |
| #2 worked-example end-to-end + second-run cache | `./mill kymora.workflow.jvm.test` covers `EngineTests`, `PersistentEngineTests`, `CommandEngineTests` | PASS | Second-invocation HIT verified via `TaskCached` event assertion; typed cached and persistent hits decode stored values without re-running bodies. |
| #3 cache controls | `CacheControlsTests`, `ConfigTests` | PASS | `purge` / `clean` exercised end-to-end; `bypass` / `readOnly` / `noCache` covered as `Config` shape (no engine wiring beyond the flag plumbing). |
| #4 macro literal validation | `TaskIdMacroTests`, `TaskScopeTests`, `ValidationTests`, `TaskIdTests` | PASS | Bad inputs (`"foo/bar"`, `"..foo"`, `"index"`) fail compilation; equivalent `.parse` returns `Result.Failure`. |
| #5 reporter event sequences | `ConsoleReporterTests`, `JsonLinesReporterTests`, `ReporterTests`, `WorkflowEventTests` | PASS | `format` / `toJson` covered as pure helpers; success / cached / failed event sequences asserted via `TestReporter` in `EngineTests`. |
| #6 no regression in core / vfs | `./mill kymora.core.jvm.{compile,test}`, `./mill kymora.vfs.jvm.{compile,test}` | PASS | core compile clean; vfs 83 / 83 tests pass; core test count below. |
| #7 `Task.persistent` round-trip | `PersistentEngineTests`, `TaskPersistentTests`, `VfsDirStoreTests` | PASS | Persistent bodies receive the real `.dest`, retain contents across invalidating runs, retain partial output on failure, and serialize same-key workspace access. |
| #8 CLI surface (replaces `Command.cli` mainargs + `runCli`) | `CommandEngineTests`, `cli.CliTests` | PASS | `Task.cli` + `Workflow.runCli` removed and replaced by parameterized `Task.command[A, P]` + `io.eleven19.kymora.workflow.cli.Cli.runWith` backed by kyo-case-app (resolves #4 + #5). |
| #9 examples end-to-end | `./mill kymora.examples.jvm.test` covers `SmileBuildTests`, `AgentSkillsTests` | PASS | 6 / 6 example tests passing. `smile-build` and `agent-skills` both exercise their workflows against a temp cache and assert event sequences. |

## Test totals (modules in this workspace)

Updated after the v0.1 → v0.1-followups work landed (see the
"Follow-up status" section below for the per-area summary).

| Module | Suite | Tests | Pass | Fail |
|---|---|---|---|---|
| `kymora-workflow` | `kymora.workflow.jvm.test` | 196 | 196 | 0 |
| `kymora-workflow-testkit` | `kymora.workflow-testkit.jvm.test` | 26 | 26 | 0 |
| `kymora-vfs` | `kymora.vfs.jvm.test` | 83 | 83 | 0 |
| `kymora-examples` | `kymora.examples.jvm.test` | 6 | 6 | 0 |
| `kymora-core` | `kymora.core.jvm.test` | 1 | 1 | 0 |
| **Total** | — | **312** | **312** | **0** |

Cross-platform verification beyond JVM:

| Target | Result |
|---|---|
| `kymora.workflow.js.test` | PASS (196 tests green) |
| `kymora.workflow.wasm.test` | PASS (196 tests green) |
| `kymora.workflow.native.test` | PASS (196 tests green) |
| `kymora.workflow-testkit.js.test` | PASS (26 tests green) |
| `kymora.workflow.js.compile` | PASS |
| `kymora.workflow.native.compile` | PASS |
| `kymora.workflow-testkit.native.compile` | PASS |
| `kymora.examples.jvm.compile` | PASS |
| `kymora.core.jvm.compile` | PASS |
| `kymora.vfs.jvm.compile` | PASS |

## Follow-up status (2026-06-17)

Updates from the v0.1-followups branch. Items marked **CLOSED** are
fully resolved on the `workflow-v0.1-followups` bookmark.

### Closed since v0.1 baseline

| Issue | Title | Commit |
|---|---|---|
| #5 §3 | **CLOSED** — `Task.Source` uses VFS content hash, not path-string | `tsvvorvr` |
| #5 §4 | **CLOSED** — mainargs derivation replaced by kyo-case-app via `Cli.runWith` | `rmtqnpzl` |
| #5 §5 | **CLOSED** — multi-command CLI dispatch via `Cli.runCommands` + `CliParseError.UnknownCommand` | `rmtqnpzl` |
| #5 §8 | **CLOSED** — BLAKE3 cross-platform via `pt.kcry::blake3` | `lstnqupo` |
| #5 §9 | **CLOSED** — `kyo.Command` shadow sidestepped by consolidating all task kinds under `Task.<kind>` factories | `uutwknqs` |
| #5 §10 | **CLOSED** — `Reporter` family renamed to `Observer` (also removes `kyo.test.TestReporter` shadow) | `oylwmuqr` |
| #5 §11 | **CLOSED** — explicit `Schema` givens for opaque types | `xxtxunus` |
| #5 §12 | **CLOSED** — `VfsDirStore` persistent locks are process-wide per root/key + `Semaphore`-backed `PersistentMutex` on JVM/Native and cooperative async mutex on JS/WASM | `kzlzvpnx`, `zruvktuo`, `ompukkyl` |
| #7 | **CLOSED** — `WorkflowSpec` testkit base class with 3-min default per-test timeout | `qwmunmsx` |

### Open (deferred to next follow-up cycle)

- **#5 §1 cross-run value persistence:** scheduler treats any existing
  manifest as HIT without comparing the stored inputs hash against the
  recomputed one. The Source layer now contributes the right fingerprint
  (per §3); the next step is wiring it through manifest equality.
- **#5 §6 fiber-per-node fan-out:** parallelism still bounded per node's
  dep list. Whole-DAG fan-out is the next big scheduler refactor.

### Resolved after this acceptance log

- **#5 §7 / #14 `Config.continueOnError`:** the worklist scheduler now
  accumulates task-level failures into `WorkflowError.Partial` when
  `continueOnError = true`. Independent siblings continue, failed-node
  dependents are cancelled with `WorkflowError.TaskCancelled`, and
  `WorkflowEvent.TaskFailed` / `TaskCancelled` events are emitted before the
  final partial abort.

### Net new in v0.1-followups (not tracked by an issue)

- **`Task.Sources` (plural)** — Mill `Sources` analogue producing
  ordered `Chunk[VPathRef]` from a `VPath*` vararg list, with an
  order-sensitive aggregate fingerprint. `Task.sourcesQuick` mirrors
  `Task.sourceQuick`. Wired into the scheduler, the dispatch table, and
  the `Graph` walker. Lands as `vtktyymr`.
- **`Workflow.Services` type alias** + companion. Splits service-like
  dependencies (`Vfs`, `CacheStore`, `Observer`) off `Workflow.Config`
  into separate `Env` effects; `Config` now carries pure run-level
  configuration. `Services.Bundle` + `Services.{init,default,layer,provide}`
  are the construction helpers. Lands as `yztwukmr`.
- **Engine on Kyo's `Clock`/`Console` effects** — the five existing
  `java.time.Instant.now()` call sites in `Scheduler` and the two
  `System.out.println` call sites in `ConsoleObserver` / `JsonLinesObserver`
  migrate to `Clock.now` / `Console.printLine` so `TestClock` and a
  future capturing-console testkit can drive deterministic tests
  without leaking real wall-clock time or stdout. Lands as `vtktyymr`.
- **`ParseError` extends `RuntimeException`** with a structured
  `description` + `detailedDescription: Maybe[String]` per variant
  (carrying expected wire form / known-good values) so Schema `readFn`
  callers throw a typed error and pattern-match consumers can read
  either field directly. Lands as `pqwmznsp`.
- **`TaskContext` trimmed** to a single `dest: VPath` field — the prior
  `emit` / `clock` fields were threaded through but never read by any
  body. Lands as `yqpoptur`.

## Known intermittent flake (resolved)

The earlier `openPersistentWorkspace retains content across calls`
flake under heavy parallel mill load is **closed**: root cause was
`ReentrantLock` thread-affinity under Kyo's fiber scheduler. Replaced
with a `Semaphore(1, fair=true)`-backed `PersistentMutex` on JVM/Native
and a cooperative async mutex on JS/WASM. The store now also serializes
two `VfsDirStore` instances sharing the same root.
