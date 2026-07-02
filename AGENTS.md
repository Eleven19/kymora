# Kymora — agent instructions

Kymora is a cross-platform Scala 3 library built with [Mill](https://mill-build.org).
This file is the shared source of truth for agents working in this repo.

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

Use the bundled `./mill` wrapper (Mill 1.2.0-RC1, JDK Temurin 25 — auto-provisioned).
When using Nushell, run Mill through the Nushell launcher: `nu mill.nu <task>`.

```sh
./mill resolve __                  # list the module tree
./mill kymora.vfs.jvm.compile      # compile a module (platform: jvm | js | wasm | native)
./mill kymora.vfs.jvm.test         # run a module's tests
./mill __.compile                  # compile everything
```

```nu
nu mill.nu resolve __                  # list the module tree
nu mill.nu kymora.vfs.jvm.compile      # compile a module (platform: jvm | js | wasm | native)
nu mill.nu kymora.vfs.jvm.test         # run a module's tests
nu mill.nu __.compile                  # compile everything
```

Verify changes with `compile` + `test` on at least the `jvm` platform of any
module you touch before claiming completion.

## Reference source exploration

When a task requires understanding how Kyo, Mill, or another upstream codebase
or library works, invoke the project-local `.claude/skills/reference-repos`
skill contextually. Use it to discover, add, update, and inspect pinned source
checkouts under `.ref/`.

Prefer direct lookup in those source checkouts when answering implementation
questions, mapping APIs to behavior, or debugging dependency interactions. For
Kyo and Mill especially, use reference source lookup before decompilation,
generated sources, jar inspection, or ad hoc web searches; checked-out upstream
code is usually more accurate and easier to correlate with published artifacts.

## Working documents (HTML)

Produce planning, design, spec, RFC, and similar working documents as
**self-contained HTML**, not Markdown. This adopts the HTML-artifact mechanism
described in [_The unreasonable effectiveness of HTML_](https://claude.com/blog/using-claude-code-the-unreasonable-effectiveness-of-html):
a single `.html` file with inline `<style>` and `<script>`, no build step, no
external assets, no CDN, no web fonts — open it directly in a browser.

- **Design system.** Start from the committed template
  [`docs/dev-templates/design-system.html`](docs/dev-templates/design-system.html).
  Copy the whole file and fill it in; do **not** reskin per document or link out
  to the template. Keep the `:root` palette and type stack. Use the shared
  component vocabulary — sticky-TOC shell, numbered sections, `.meta` header with
  a status `.badge`, `.callout` (`warn` / `risk` / `ok`), requirement tables with
  `.rid` + `MUST` / `SHOULD` / `MAY`, `.decision` cards, inline SVG `figure`
  diagrams, `.frames` byte layouts, and for plan docs `.task` / `.step`
  checkboxes with a TOC `.progress` readout. Delete the components a document
  does not need; do not invent parallel ones.
- **Scala code.** Fence in `<pre><code class="scala">…</code></pre>`; the
  highlighter script bundled in the template colors it. Use `class="text"` for
  non-Scala blocks.
- **Location and naming.** These are working artifacts, not repository content:
  write them under `.dev/` (git-excluded), organized by kind —
  `.dev/specs/`, `.dev/plans/`, `.dev/designs/`. Name files
  `YYYY-MM-DD-<slug>.html`.
- **Exemplars.** The Kyo checkout carries reference documents in this system
  under its own `.dev/` (`.dev/specs/…-design.html`,
  `.dev/plans/…-phase1.html`) — mirror their structure and depth.

## Layout

- `build.mill.yaml` — Mill + JVM version (meta-build config).
- `mill-build/src/build/` — **shared build traits**, available under the `build.*`
  package to every `package.mill`:
  - `Modules.scala` — `KymoraVersions` (dependency pins), `CommonScalaModule`
    (Scala version, scalac options), and the JS / Wasm / Native module traits.
  - `PublishSupport.scala` — Sonatype Central publishing (`io.eleven19.kymora`).
- `kymora/<module>/package.mill` — one per module, cross-platform
  (`jvm` / `js` / `wasm` / `native` objects). Sources in `kymora/<module>/src`, tests in
  `kymora/<module>/test/src`.
- `kymora/kyo/mill` — JVM-only published Mill plugin artifact
  (`kymora-kyo-mill`) for downstream Kyo users.
- `kymora/package.mill.yaml` — the published `kymora` umbrella aggregate.

Current modules: `kymora-vfs` (`kymora/vfs`), `kymora-workflow`
(`kymora/workflow`, depends on `vfs`) and its `kymora-workflow-testkit`
(`kymora/workflow-testkit`) companion, plus `kymora-kyo-mill`
(`kymora/kyo/mill`). There is also an unpublished `kymora-examples`
(`kymora/examples`, JVM-only) carrying runnable reference examples
(`smile-build`, `agent-skills`).

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
- Tests use **kyo-test** (Kyo's own framework). The meta-build currently
  provides internal `KyoTestModule` / `KyoTestJSModule` /
  `KyoTestWasmModule` / `KyoTestNativeModule` traits in `Modules.scala`; the
  published downstream equivalent lives in `kymora-kyo-mill`. They set the
  per-platform `sbt.testing.Framework` class and pull `kyo-core` +
  `kyo-test-api` + `kyo-test-runner`. WASM tests require Node 24+. Mix the
  matching trait into each platform's `test` object. Suites extend
  `kyo.test.Test[Any]`; import `kyo.*` and `kyo.test.*`, write
  `"name" in { assert(...) }`.
- Pin shared dependency versions in `KymoraVersions`, not inline in modules.

### Adding a module

Mirror an existing `kymora/<module>/package.mill` (e.g. `kymora/vfs`): a
`<Name>Module` trait extending `CommonScalaModule`, `KymoraPlatformScalaModule`,
and `PublishSupport`; a test trait; and `jvm` / `js` / `wasm` / `native`
objects. Add it to the `kymora` aggregate in `kymora/package.mill.yaml` if it
should be published.
