package io.eleven19.kymora.examples.smilebuild

import io.eleven19.kymora.workflow.*

/** The wired build graph for the smile-build example.
  *
  * Two stub Mill-like modules:
  *
  *   - `core` has no upstream dependencies.
  *   - `app` depends on `core` — its `compile` consumes `core.compile`, which
  *     in turn means asking for `app.jar` walks the full graph in dependency
  *     order through the workflow engine.
  *
  * This is the kymora-workflow analogue of a tiny Mill build file. Real
  * builds would extend the same trait with non-stub bodies (scalac, jar,
  * test runners) and richer dep wiring.
  */
object Build:

  /** Leaf module — depends on nothing else. */
  object core extends SmileModule(name = "core")

  /** Application module — depends on `core`. */
  object app extends SmileModule(name = "app", deps = Seq(core))

  // CLI entry point lives under its own `smile` scope, separate from the
  // per-module `smile.<name>` scopes that the modules themselves use.
  private given TaskScope = TaskScope.unsafe("smile")

  /** CLI-driven entry point. `Workflow.runCli` threads the token list into
    * the body; the trivial `CommandArgs[Unit]` instance treats any tokens as
    * a no-op so callers can wire this into `Workflow.runCli(cli, args)` with
    * an arbitrary command line.
    *
    * The body returns a one-line descriptor — a real-world CLI would dispatch
    * on a sub-command and trigger the appropriate `Workflow.run` over one of
    * the module tasks. */
  val cli: Task.Command[String] = Task.cli[Unit, String]("cli") { _ =>
    "smile-build CLI: targets = [core.compile, core.jar, app.compile, app.jar]"
  }

end Build
