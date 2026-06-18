package build

import mill.*
import mill.scalalib.*
import mill.scalajslib.*
import mill.scalanativelib.*
import coursier.maven.MavenRepository

object KymoraVersions:
    /** Pinned Kyo release candidate. All Kyo modules ship at the same version —
      * pinned together here. */
    val Kyo: String = "1.0.0-RC4"

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

trait KymoraPlatformScalaModule extends PlatformScalaModule {
  def sharedPlatformCrossSuffixes: Seq[String] = Seq("js", "jvm", "native")

  override def sourcesFolders: Seq[os.SubPath] =
    val platform = platformCrossSuffix
    val shared =
      if sharedPlatformCrossSuffixes.contains(platform) then
        sharedPlatformCrossSuffixes
          .filterNot(_ == platform)
          .map(other => Seq(platform, other).sorted.mkString("-"))
          .distinct
          .sorted
          .map(suffix => os.SubPath(s"src-$suffix"))
      else Seq.empty

    super.sourcesFolders ++ shared
}

trait CommonScalaTestModule extends ScalaModule with scalafmt.ScalafmtModule

/** kyo-test wiring for Mill.
  *
  * Kyo ships no Mill integration, so we declare the `sbt.testing.Framework`
  * class names and the kyo-test dependencies here. `kyo-test-api` provides the
  * `kyo.test.Test` suite API; `kyo-test-runner` provides the platform
  * `Framework` implementations referenced below.
  *
  * Framework class per platform (package `kyo.test.runner`):
  *   - JVM       -> SbtFramework
  *   - JS / Wasm -> JsFramework
  *   - Native    -> NativeFramework
  *
  * Mix the matching trait into each platform's test object:
  *   object test extends ScalaTests       with KyoTestModule
  *   object test extends ScalaJSTests      with KyoTestJSModule
  *   object test extends ScalaNativeTests  with KyoTestNativeModule
  */
trait KyoTestModule extends TestModule {
  def kyoVersion: T[String] = Task { KymoraVersions.Kyo }

  override def testFramework: T[String] = "kyo.test.runner.SbtFramework"

  // Kyo's schema derivation uses LambdaMetafactory against private fields in
  // java.base, which JDK 17+ closes by default. Without --add-opens the
  // derivation hangs (encode loop on first Schema construction) instead of
  // failing cleanly. Apply to every kyo-test JVM run.
  override def forkArgs: T[Seq[String]] = Task {
    super.forkArgs() ++ Seq("--add-opens", "java.base/java.lang=ALL-UNNAMED")
  }

  override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
    super.mandatoryMvnDeps() ++ Seq(
      // kyo-test-api/-runner declare the kyo effect modules as `provided`, so the
      // consumer must supply them. kyo-core transitively pulls kyo-prelude /
      // kyo-kernel / kyo-data, which define `<`, Async, Abort, Scope, Frame.
      mvn"io.getkyo::kyo-core::${kyoVersion()}",
      mvn"io.getkyo::kyo-test-api::${kyoVersion()}",
      mvn"io.getkyo::kyo-test-runner::${kyoVersion()}"
    )
  }
}

trait KyoTestJSModule extends KyoTestModule {
  override def testFramework: T[String] = "kyo.test.runner.JsFramework"
}

trait KyoTestNativeModule extends KyoTestModule {
  override def testFramework: T[String] = "kyo.test.runner.NativeFramework"
}

trait CommonScalaJSModule extends ScalaJSModule with scalafmt.ScalafmtModule {
  def scalaJSVersion = "1.21.0"

  // CommonJSModule (default: NoModule). Required because kyo-core's
  // `Path` Scala.js backend imports `node:path` and needs a real CJS
  // `require`. NoModule output drops the import declarations and the
  // linker refuses any code that reaches the Node path bindings —
  // hits as soon as testkit's TestVfs.tempDir is referenced.
  override def moduleKind: T[mill.scalajslib.api.ModuleKind] =
    Task { mill.scalajslib.api.ModuleKind.CommonJSModule }
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
