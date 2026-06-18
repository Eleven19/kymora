# Kymora — agent instructions

Kymora is a cross-platform Scala 3 library built with [Mill](https://mill-build.org).
This file is the source of truth for agents working in this repo; `AGENTS.md` is a
symlink to it.

## Version control: use Jujutsu (`jj`) by default

**This repo uses `jj` for version control, not raw `git`.** The repo is colocated
(it has a real `.git`), so `git` still works as a fallback, but default to `jj`.

- Full clone / branch / PR workflow lives in the **Developing** section of
  [`README.md`](README.md) — follow it.
- Key facts: the working copy is itself a commit (no staging, no `git add`);
  `jj` uses **bookmarks**, not branches; describe changes with `jj describe -m`.
- Push a feature: create a bookmark (`jj bookmark create <name> -r @`), then
  `jj git push --bookmark <name>`. (This `jj` version has **no** `--allow-new`
  flag — pushing a new bookmark just works.)
- Open the PR with `gh pr create --base main --head <bookmark>`.
- Do **not** push to `main` directly for feature work — branch via a bookmark
  and open a PR.

## Build (Mill)

Use the bundled `./mill` wrapper (Mill 1.1.6, JDK Temurin 25 — auto-provisioned).
When using Nushell, run Mill through the Nushell launcher: `nu mill.nu <task>`.

```sh
./mill resolve __                  # list the module tree
./mill kymora.core.jvm.compile     # compile a module (platform: jvm | js | native)
./mill kymora.core.jvm.test        # run a module's tests
./mill __.compile                  # compile everything
```

```nu
nu mill.nu resolve __                  # list the module tree
nu mill.nu kymora.core.jvm.compile     # compile a module (platform: jvm | js | native)
nu mill.nu kymora.core.jvm.test        # run a module's tests
nu mill.nu __.compile                  # compile everything
```

Verify changes with `compile` + `test` on at least the `jvm` platform of any
module you touch before claiming completion.

## Layout

- `build.mill.yaml` — Mill + JVM version (meta-build config).
- `mill-build/src/build/` — **shared build traits**, available under the `build.*`
  package to every `package.mill`:
  - `Modules.scala` — `KymoraVersions` (dependency pins), `CommonScalaModule`
    (Scala version, scalac options), and the JS / Wasm / Native module traits.
  - `PublishSupport.scala` — Sonatype Central publishing (`io.eleven19.kymora`).
- `kymora/<module>/package.mill` — one per module, cross-platform
  (`jvm` / `js` / `native` objects). Sources in `kymora/<module>/src`, tests in
  `kymora/<module>/test/src`.
- `kymora/package.mill.yaml` — the published `kymora` umbrella aggregate.

Current modules: `kymora-core` (`kymora/core`), `kymora-vfs` (`kymora/vfs`,
depends on `core`), `kymora-workflow` (`kymora/workflow`, depends on
`core` + `vfs`) and its `kymora-workflow-testkit` (`kymora/workflow-testkit`)
companion. There is also an unpublished `kymora-examples` (`kymora/examples`,
JVM-only) carrying runnable reference examples (`smile-build`, `agent-skills`).

`kymora-workflow` is the DAG / incremental-execution library; its
`kymora-workflow-testkit` companion publishes the in-memory cache, fake clock,
event capture, and graph-builder ObjectMothers. **Test helpers go in the testkit,
not in private test source** — downstream users will need them too, so anything
generally useful for testing workflows belongs in the published testkit module.

## Conventions

- **Scala 3.8.4**, set centrally in `CommonScalaModule`. Change the version
  there, not per-module.
- Compiler is strict: `-Werror`, `-language:strictEquality`,
  `-Wvalue-discard`, `-Wnonunit-statement`. Code must be warning-clean.
- Format with scalafmt (config in `.scalafmt.conf`); `CommonScalaModule` mixes in
  `ScalafmtModule`.
- Tests use **kyo-test** (Kyo's own framework). Kyo ships no Mill support, so the
  meta-build provides `KyoTestModule` / `KyoTestJSModule` / `KyoTestNativeModule`
  (in `Modules.scala`) — they set the per-platform `sbt.testing.Framework` class
  and pull `kyo-core` + `kyo-test-api` + `kyo-test-runner`. Mix the matching trait
  into each platform's `test` object. Suites extend `kyo.test.Test[Any]`; import
  `kyo.*` and `kyo.test.*`, write `"name" in { assert(...) }`.
- Pin shared dependency versions in `KymoraVersions`, not inline in modules.

### Adding a module

Mirror an existing `kymora/<module>/package.mill` (e.g. `kymora/vfs`): a
`<Name>Module` trait extending `CommonScalaModule` + `PlatformScalaModule` +
`PublishSupport`, a test trait, and `jvm` / `js` / `native` objects. Add it to
the `kymora` aggregate in `kymora/package.mill.yaml` if it should be published.
