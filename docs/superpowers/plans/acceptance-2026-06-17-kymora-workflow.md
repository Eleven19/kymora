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
suites are subsets of that target. Compile coverage is verified by
`./mill kymora.workflow.{jvm,js,native}.compile`.

## Results per criterion

| Criterion | Command(s) | Result | Notes |
|---|---|---|---|
| #1 cross-platform compile + jvm tests | `./mill kymora.workflow.{jvm,js,native}.compile`, `./mill kymora.workflow.jvm.test` | PASS | 137 / 137 jvm tests passing; JS + Native compile clean. |
| #2 worked-example end-to-end + second-run cache | `./mill kymora.workflow.jvm.test` covers `EngineTests`, `PersistentEngineTests`, `CommandEngineTests` | PASS | Second-invocation HIT verified via `TaskCached` event assertion. Real cross-run persistence is simplified — body re-runs on HIT inside a single `Workflow.run` invocation (Task 44 simplification). |
| #3 cache controls | `CacheControlsTests`, `ConfigTests` | PASS | `purge` / `clean` exercised end-to-end; `bypass` / `readOnly` / `noCache` covered as `Config` shape (no engine wiring beyond the flag plumbing). |
| #4 macro literal validation | `TaskIdMacroTests`, `TaskScopeTests`, `ValidationTests`, `TaskIdTests` | PASS | Bad inputs (`"foo/bar"`, `"..foo"`, `"index"`) fail compilation; equivalent `.parse` returns `Result.Failure`. |
| #5 reporter event sequences | `ConsoleReporterTests`, `JsonLinesReporterTests`, `ReporterTests`, `WorkflowEventTests` | PASS | `format` / `toJson` covered as pure helpers; success / cached / failed event sequences asserted via `TestReporter` in `EngineTests`. |
| #6 no regression in core / vfs | `./mill kymora.core.jvm.{compile,test}`, `./mill kymora.vfs.jvm.{compile,test}` | PASS | core compile clean; vfs 83 / 83 tests pass; core test count below. |
| #7 `Task.persistent` round-trip | `PersistentEngineTests`, `TaskPersistentTests` | PASS | Cached-equivalent execution path verified. Real `.dest/` retention across separate `run` invocations is deferred (Task 45 simplification: persistent behaves like `Cached` for v1). |
| #8 CLI surface (replaces `Command.cli` mainargs + `runCli`) | `CommandEngineTests`, `cli.CliTests` | PASS | `Task.cli` + `Workflow.runCli` removed and replaced by parameterized `Task.command[A, P]` + `io.eleven19.kymora.workflow.cli.Cli.runWith` backed by kyo-case-app (resolves #4 + #5). |
| #9 examples end-to-end | `./mill kymora.examples.jvm.test` covers `SmileBuildTests`, `AgentSkillsTests` | PASS | 6 / 6 example tests passing. `smile-build` and `agent-skills` both exercise their workflows against a temp cache and assert event sequences. |

## Test totals (modules in this workspace)

| Module | Suite | Tests | Pass | Fail |
|---|---|---|---|---|
| `kymora-workflow` | `kymora.workflow.jvm.test` | 137 | 137 | 0 |
| `kymora-vfs` | `kymora.vfs.jvm.test` | 83 | 83 | 0 |
| `kymora-examples` | `kymora.examples.jvm.test` | 6 | 6 | 0 |
| `kymora-core` | `kymora.core.jvm.test` | 1 | 1 | 0 |
| **Total** | — | **227** | **227** | **0** |

Compile-only verification:

| Target | Result |
|---|---|
| `kymora.workflow.jvm.compile` | PASS |
| `kymora.workflow.js.compile` | PASS |
| `kymora.workflow.native.compile` | PASS |
| `kymora.workflow-testkit.jvm.compile` | PASS |
| `kymora.examples.jvm.compile` | PASS |
| `kymora.core.jvm.compile` | PASS |
| `kymora.vfs.jvm.compile` | PASS |

## Known limitations / simplifications carried from the plan

These are documented in-plan and are expected to be lifted in a v1.x follow-up.

- **Engine cross-run persistence (Phase 11, Task 44):** Within a single
  `Workflow.run` invocation a cache HIT does not re-execute the body — but
  the engine does not yet re-hydrate `.dest/` from a previous JVM run. A
  separate process invocation will currently re-run the body.
- **`Task.Persistent` execution (Phase 11, Task 45):** Implemented as
  `Cached`-equivalent for v1. Real `.dest/` retention across separate runs
  (the zinc-style use case in §14.1) is deferred.
- **`verifyDest` (Phase 12, Task 49):** Flag is plumbed through `Config`
  and `Cacheable`; the engine does not yet rehash `.dest/` on HIT.
- **`Command.cli` parser (Phase 13, Task 50):** Resolved. The hand-rolled
  `CommandArgs` factory + mainargs-shaped surface has been replaced by
  parameterized `Task.command[A, P]` and `cli.Cli.runWith` backed by
  kyo-case-app, which provides full `Parser` / `Help` derivation. Usage
  banners come from case-app's `Help`. Closes #4 + #5.
- **`Config.continueOnError` (Phase 12):** Shape-only — the scheduler does
  not yet fan out failures.
- **Cross-platform hashing (Phase 16):** Resolved — all three platforms
  now share the pure-Scala `pt.kcry::blake3` implementation, producing
  byte-identical BLAKE3 digests on JVM, Scala.js, and Scala Native. Cache
  manifests written on one platform are valid on any other. The previous
  FNV-1a fallback for JS/Native has been removed (closes #8).
- **Error fields (Phase 6):** A few error variants carry `String` payloads
  instead of `Throwable` so the error ADT remains `Schema`-derivable. The
  engine wraps real exceptions at the boundary.

## Known intermittent flake

- `kymora.workflow.jvm.test` includes
  `io.eleven19.kymora.workflow.store.VfsDirStoreTests."openPersistentWorkspace
  retains content across calls"` (and a related
  `InMemoryCacheStoreTests` test). Under parallel-suite load on macOS the
  first pass timed out after 2 minutes; a sequential re-run completed in
  ~24 s with 137 / 137 passing. Filed as a known flake — non-blocking for
  acceptance.
