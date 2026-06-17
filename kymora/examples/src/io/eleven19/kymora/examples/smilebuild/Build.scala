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

  /** Entry-point Command for the build. Returns a one-line descriptor — a
    * real-world CLI would dispatch on a sub-command and trigger the
    * appropriate `Workflow.run` over one of the module tasks.
    *
    * To wire CLI argument parsing, define a case class with
    * `caseapp.Parser` + `caseapp.Help` derivations and use the
    * parameterized variant
    * `Task.command[String, Args]("cli") { args => ... }` together with
    * `io.eleven19.kymora.workflow.cli.Cli.runWith`.
    */
  val cli: Task.Command[String] = Task.command("cli") {
    "smile-build CLI: targets = [core.compile, core.jar, app.compile, app.jar]"
  }

end Build
