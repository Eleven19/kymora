# kymora

Kymora provides cross-platform extensions for the
[Kyo](https://github.com/getkyo/kyo) effect system. The current modules focus
on virtual filesystem access and workflow/task execution: programs describe the
effects they need, and callers choose concrete runtimes such as in-memory,
host-backed, or mounted filesystems.

## Modules

- [`kymora-vfs`](kymora/vfs) — Kyo effects for read-only and writable virtual
  filesystem access over host, in-memory, and mounted backends.
- [`kymora-workflow`](kymora/workflow) — Kyo-native DAG task-graph engine with
  Mill-aligned incremental caching. See the
  [design spec](docs/superpowers/specs/2026-06-16-kymora-workflow-design.md).
- [`kymora-workflow-testkit`](kymora/workflow-testkit) — published test helpers for
  `kymora-workflow` (in-memory cache, fake clock, event capture, graph
  ObjectMothers).
- `kymora-examples` (JVM-only, unpublished) — runnable reference examples:
  `smile-build` (Mill-style build DSL) and `agent-skills` (workflow-backed agent
  skill runner).

## Quick examples

Use VFS path syntax with the smallest effect that fits the program:

```scala
import io.eleven19.kymora.vfs.*
import kyo.*

val program =
  for
    _    <- (VPath.root / "notes.txt").write("hello")
    text <- (VPath.root / "notes.txt").read
  yield text

val result =
  for
    backend <- Vfs.inMemory.init
    text    <- Vfs.run(backend)(program)
  yield text
```

Run workflows by constructing a `Workflow.Runtime` and handling the `Workflow`
effect:

```scala
import io.eleven19.kymora.vfs.*
import io.eleven19.kymora.workflow.*
import kyo.*

val compile = Task.cached("compile") {
  for
    dest <- Workflow.dest
    out   = dest / "classes.txt"
    _    <- out.write("compiled")
  yield out
}

val run =
  for
    vfs <- Vfs.inMemory.init
    runtime = Workflow.Runtime(vfs)
    output <- Workflow.handle(runtime)(compile())
  yield output
```

## Developing

This project uses [Jujutsu (`jj`)](https://jj-vcs.github.io/jj/) as its default
version control system. Because `jj` is backed by Git and fully compatible with
it, the repository is also a normal Git repository — if you prefer Git, every
standard `git` command still works against the same `.git` store. The
instructions below assume `jj`.

### Prerequisites

- Install `jj`:

  ```sh
  # macOS (Homebrew)
  brew install jj

  # or via cargo
  cargo install --locked jj-cli
  ```

- Set your identity (once, globally):

  ```sh
  jj config set --user user.name "Your Name"
  jj config set --user user.email "you@example.com"
  ```

### Cloning

Clone with a colocated Git directory so both `jj` and `git` tooling work side by
side:

```sh
jj git clone --colocate git@github.com:Eleven19/kymora.git
cd kymora
```

Drop `--colocate` if you don't need raw `git` commands locally.

### Development flow

In `jj` there is no staging area and the working copy is itself a commit. You
edit files, describe the change, and push a **bookmark** (jj's equivalent of a
Git branch) to open a PR.

1. **Start a feature.** Create a change on top of `main` and describe it:

   ```sh
   jj new main -m "Add the thing"
   ```

   Edit files as normal. `jj` automatically snapshots the working copy — there
   is no `git add`.

2. **Inspect your work** as you go:

   ```sh
   jj status        # what changed in the working copy
   jj diff          # the diff of the current change
   jj log           # history and where you are (@)
   ```

   Refine the description any time with `jj describe -m "..."`. Split a change
   that grew too big with `jj split`.

3. **Create a bookmark** to publish under (GitHub needs a branch name):

   ```sh
   jj bookmark create my-feature -r @
   ```

   If you keep working, move the bookmark to your latest change with
   `jj bookmark move my-feature --to @`.

4. **Push to GitHub** and open a PR:

   ```sh
   jj git push --bookmark my-feature --allow-new
   gh pr create --fill --base main --head my-feature
   ```

   Use `--allow-new` only the first time the bookmark is pushed.

5. **Update the PR** after more work — re-describe/refine, point the bookmark at
   your newest change, and push again:

   ```sh
   jj bookmark move my-feature --to @
   jj git push --bookmark my-feature
   ```

6. **Keep up to date with `main`:**

   ```sh
   jj git fetch
   jj rebase -d main          # rebase your change(s) onto the latest main
   ```

### Replacing Git worktrees (parallel / agentic work)

If you reach for `git worktree` to run several agents or feature branches in
parallel without clobbering one working tree, `jj` gives you two better-fitting
options.

**Often you don't need a separate checkout at all.** In `jj`, every change is a
commit and switching between them is cheap and conflict-free — no stashing, no
dirty-tree blocking. To juggle multiple in-flight features in a single
directory:

```sh
jj new main -m "Feature A"     # work on A
jj new main -m "Feature B"     # start B off main, independent of A
jj edit <change-id-of-A>       # jump back to A's working copy any time
jj log                         # see all parallel changes at once
```

Each `jj new main` starts an independent line of work; `jj edit` makes any
existing change the working copy. Nothing is lost when you switch.

**When you genuinely need separate working directories** (e.g. one per agent,
each running its own build/test process against different files at the same
time), use a **workspace** — `jj`'s direct equivalent of a Git worktree, sharing
one underlying repository:

```sh
jj workspace add ../kymora-agent-1
jj workspace add ../kymora-agent-2
jj workspace list              # show all workspaces
jj workspace forget ../kymora-agent-1   # detach when done
```

Each workspace has its own working-copy commit but shares the same operation log
and commits, so changes made in one are immediately visible to the others via
`jj log`. This avoids the duplicated-clone overhead and branch-checkout
contention that make `git worktree` awkward for many parallel agents.

### Using Git instead

The repo is a standard Git repository, so `git clone`, `git checkout -b`,
`git commit`, `git push`, and `gh pr create` all work normally. You and
`jj`-using contributors can collaborate on the same branches without conflict.
