package io.eleven19.kymora.examples.smilebuild

import io.eleven19.kymora.vfs.VPath
import io.eleven19.kymora.workflow.*

/** A toy Mill-like build module backed by kymora-workflow Tasks.
  *
  * Each module exposes the canonical Mill quartet — `sources`, `compile`,
  * `jar`, `test` — wired as kymora-workflow primitives:
  *
  *   - `sources` is a [[Source]] pointing at a synthetic `repo/<name>/src`
  *     VFS path. The real-world equivalent would observe a working copy.
  *   - `compile` is a [[Task]] that depends on `sources` (and, transitively,
  *     on each upstream module's `compile`). Its body is a stub that returns
  *     a descriptor String — a real implementation would invoke scalac/zinc.
  *   - `jar` depends on `compile` and returns a String descriptor — a real
  *     implementation would package the classpath into a JAR.
  *   - `test` is a [[Command]] (always-runs) that depends on `compile` and
  *     returns a synthetic "tests pass" string — a real implementation would
  *     invoke a test runner.
  *
  * Module dependencies are wired by passing other `SmileModule`s in the
  * `deps` sequence. For arity simplicity the stub `compile` only consumes
  * the first upstream module's `compile`; multi-dep wiring would mirror the
  * pattern used in higher-arity `Task.init` overloads.
  *
  * The TaskScope for each module is `smile.<name>` — assembled directly via
  * [[TaskScope.unsafe]] so the trait can stand alone (no surrounding
  * `Workflow.scope` block needed). Validation of the name is the caller's
  * responsibility — the canonical entry points in [[Build]] use static names.
  */
abstract class SmileModule(val name: String, val deps: Seq[SmileModule] = Seq.empty):

  private given TaskScope = TaskScope.unsafe(s"smile.$name")

  /** Synthetic source directory at `repo/<name>/src`. The VFS path does not
    * need to physically exist — [[Source]] hashes the path string in this
    * slice of the engine. */
  val sources: Source = Source.init("sources")(VPath("repo", name, "src"))

  /** Stub compilation step. Returns a descriptor string of the form
    * `"classes for <name>"` (or `"classes for <name> (uses <upstream-jar>)"`
    * when there is at least one module dependency). */
  val compile: Task[String] = deps.headOption match
    case None =>
      Task.init("compile")(sources) { _ => s"classes for $name" }
    case Some(upstream) =>
      Task.init("compile")(sources, upstream.compile) { (_, depClasses) =>
        s"classes for $name (uses $depClasses)"
      }

  /** Stub JAR packaging. Returns a descriptor string of the form
    * `"jar for <name> from <classes-descriptor>"`. */
  val jar: Task[String] = Task.init("jar")(compile) { classes =>
    s"jar for $name from $classes"
  }

  /** Stub test runner. As a [[Command]] this is never memoized — every
    * run rebuilds the dep chain (cached) and re-emits the descriptor. */
  val test: Command[String] = Command.init("test")(compile) { _ =>
    s"tests pass for $name"
  }
end SmileModule
