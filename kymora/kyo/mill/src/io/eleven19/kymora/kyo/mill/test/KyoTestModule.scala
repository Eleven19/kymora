package io.eleven19.kymora.kyo.mill.test

import _root_.mill.*
import _root_.mill.scalalib.*
import io.eleven19.kymora.kyo.mill.KyoMillDefaults

/** Mill-native kyo-test wiring for JVM test modules.
  *
  * Example:
  * {{{
  * object test extends ScalaTests with KyoTestModule
  * }}}
  */
trait KyoTestModule extends TestModule:
    def kyoVersion: T[String] = Task(KyoMillDefaults.kyoVersion)

    override def testFramework: T[String] = "kyo.test.runner.SbtFramework"

    override def forkArgs: T[Seq[String]] = Task {
        super.forkArgs() ++ Seq("--add-opens", "java.base/java.lang=ALL-UNNAMED")
    }

    override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
        super.mandatoryMvnDeps() ++ Seq(
            mvn"io.getkyo::kyo-core::${kyoVersion()}",
            mvn"io.getkyo::kyo-test-api::${kyoVersion()}",
            mvn"io.getkyo::kyo-test-runner::${kyoVersion()}"
        )
    }

/** Mill-native kyo-test wiring for Scala.js test modules. */
trait KyoTestJSModule extends KyoTestModule:
    override def testFramework: T[String] = "kyo.test.runner.JsFramework"

/** Mill-native kyo-test wiring for Scala.js Wasm test modules. */
trait KyoTestWasmModule extends KyoTestJSModule

/** Mill-native kyo-test wiring for Scala Native test modules. */
trait KyoTestNativeModule extends KyoTestModule:
    override def testFramework: T[String] = "kyo.test.runner.NativeFramework"
