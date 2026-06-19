package io.eleven19.kymora.kyo.mill.wasm

import _root_.mill.*
import _root_.mill.scalajslib.*
import _root_.mill.scalalib.{Dep, DepSyntax}
import io.eleven19.kymora.kyo.mill.KyoMillDefaults

/** Scala.js module variant for publishing WebAssembly artifacts.
  *
  * The trait follows Kyo's artifact convention by publishing with the `_sjs1-wasm` platform suffix while still using
  * normal Scala.js coordinates for non-Wasm third-party dependencies when consumers choose to do so.
  */
trait KyoScalaJSWasmModule extends ScalaJSModule:
    override def scalaJSVersion: T[String] = Task(KyoMillDefaults.scalaJSVersion)

    override def scalaJSExperimentalUseWebAssembly: T[Boolean] = Task(true)

    override def platformSuffix: T[String] = Task {
        s"_sjs${artifactScalaJSVersion()}-wasm"
    }

    override def scalaLibraryMvnDeps: T[Seq[Dep]] = Task {
        Seq(mvn"org.scala-lang:scala3-library_sjs1_3:${scalaVersion()}")
    }

    override protected def resolvedDepsWarnNonPlatform: T[Boolean] = Task(false)

    override def moduleKind: T[_root_.mill.scalajslib.api.ModuleKind] = Task {
        _root_.mill.scalajslib.api.ModuleKind.ESModule
    }

    override def jsEnvConfig: T[_root_.mill.scalajslib.api.JsEnvConfig] = Task {
        _root_.mill.scalajslib.api.JsEnvConfig.NodeJs(
            args = List("--max_old_space_size=5120", "--experimental-wasm-exnref")
        )
    }
