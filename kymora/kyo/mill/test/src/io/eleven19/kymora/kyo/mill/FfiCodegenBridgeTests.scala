package io.eleven19.kymora.kyo.mill.ffi

import utest.*

object FfiCodegenBridgeTests extends TestSuite:

    def tests: Tests = Tests {
        test("tool dependencies include Kyo FFI codegen and Scala 3 compiler") {
            val deps = FfiCodegenBridge.toolDependencyCoordinates(kyoVersion = "1.0.0-RC4", scalaVersion = "3.8.4")

            assert(deps.contains("io.getkyo:kyo-ffi-codegen_3:1.0.0-RC4"))
            assert(deps.contains("org.scala-lang:scala3-compiler_3:3.8.4"))
        }
    }
