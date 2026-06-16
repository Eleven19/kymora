package build

import mill.*
import mill.scalalib.*
import mill.scalajslib.*
import mill.scalanativelib.*
import coursier.maven.MavenRepository

object KymoraVersions:
    /** Minimum required Kyo version. Later snapshot or stable releases are acceptable. */
    val Kyo: String = "1.0.0-RC2+88-0059a365-SNAPSHOT"

    /** Pinned scribe version for cross-platform logging (JVM + JS + Native). */
    val Scribe: String = "3.16.1"

trait CommonScalaModule extends ScalaModule with scalafmt.ScalafmtModule {
  override def scalaVersion = Task {
    "3.8.4"
  }

  override def scalacOptions = Task {
    Seq(
      "-Wvalue-discard",
      "-Wnonunit-statement",
      "-Wconf:msg=(unused.*value|discarded.*value|pure.*statement):error",
      "-language:strictEquality",
      "-deprecation",
      "-feature",
      "-Werror"
    )
  }

  override def repositoriesTask: Task[Seq[coursier.Repository]] = Task.Anon {
    super.repositoriesTask() ++ Seq(
      MavenRepository("https://central.sonatype.com/repository/maven-snapshots/"),
      MavenRepository("https://oss.sonatype.org/content/repositories/snapshots/")
    )
  }
}

trait CommonScalaTestModule extends ScalaModule with scalafmt.ScalafmtModule

trait CommonScalaJSModule extends ScalaJSModule with scalafmt.ScalafmtModule {
  def scalaJSVersion = "1.20.1"
}

/** Scala.js module variant that emits a Wasm GC module instead of plain JS.
  *
  * The experimental WebAssembly backend requires `ModuleKind.ESModule` and the default `ModuleSplitStyle.FewestModules`;
  * both are set here so callers only need to mix this trait in. Output is loadable in Chrome 119+, Firefox 120+, and
  * Safari 18.2+ (browsers with Wasm GC).
  *
  * @note
  *   The WebAssembly backend treats `@JSExport*` annotations differently from the JS linker:
  *   - `@JSExport` on object/class members is silently dropped — those members are not callable from JS.
  *   - `@JSExportTopLevel` on a top-level **`val`** IS honored: the linker generates an import-callback that
  *     populates the named ES-module export with the val's value. So FFI surfaces that need a populated namespace
  *     should expose it as `@JSExportTopLevel("Name") val foo: js.Object = js.Dynamic.literal(...)` rather than as an
  *     `@JSExportTopLevel object` (the latter produces an empty namespace under the Wasm linker).
  *   - `@JSExportTopLevel` on an `object` produces the ES-module export but with no members, since the per-member
  *     `@JSExport` annotations are dropped.
  */
trait CommonScalaJSWasmModule extends CommonScalaJSModule {
  override def scalaJSExperimentalUseWebAssembly: T[Boolean] = Task { true }
  override def moduleKind: T[mill.scalajslib.api.ModuleKind] =
    Task { mill.scalajslib.api.ModuleKind.ESModule }
}

trait CommonScalaNativeModule extends ScalaNativeModule with scalafmt.ScalafmtModule {
  def scalaNativeVersion = "0.5.11"
}
