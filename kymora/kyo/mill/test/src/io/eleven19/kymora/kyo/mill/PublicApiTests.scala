package io.eleven19.kymora.kyo.mill

import io.eleven19.kymora.kyo.mill.compat.*
import io.eleven19.kymora.kyo.mill.doctest.*
import io.eleven19.kymora.kyo.mill.ffi.*
import io.eleven19.kymora.kyo.mill.test.*
import io.eleven19.kymora.kyo.mill.wasm.*
import scala.reflect.ClassTag
import utest.*

object PublicApiTests extends TestSuite:

    private def thrown[A <: Throwable](f: => Any)(using ClassTag[A]): A =
        try
            val _ = f
            throw new java.lang.AssertionError("Expected exception")
        catch
            case e: A => e

    def tests: Tests = Tests {
        test("default Kyo version matches the repository pin") {
            assert(KyoMillDefaults.kyoVersion == "1.0.0-RC4+49-2eafb7d1-SNAPSHOT")
            assert(KyoMillDefaults.scalaJSVersion == "1.22.0")
        }

        test("public API exposes Mill-native Kyo test and Wasm traits") {
            val apiTypes = Seq(
                classOf[KyoTestModule],
                classOf[KyoTestJSModule],
                classOf[KyoTestWasmModule],
                classOf[KyoTestNativeModule],
                classOf[KyoScalaJSWasmModule]
            )

            assert(apiTypes.map(_.getSimpleName).contains("KyoTestModule"))
        }

        test("compat axes describe supported Kyo RC4 artifact combinations") {
            assert(CompatAxis.supported(CompatBackend.Kyo, CompatPlatform.Wasm))
            assert(CompatAxis.supported(CompatBackend.Zio, CompatPlatform.Native))
            assert(CompatAxis.supported(CompatBackend.CatsEffect, CompatPlatform.Js))
            assert(!CompatAxis.supported(CompatBackend.CatsEffect, CompatPlatform.Wasm))
            assert(!CompatAxis.supported(CompatBackend.Ox, CompatPlatform.Js))
        }

        test("doctest and compat modules are plain Mill traits") {
            val apiTypes = Seq(
                classOf[KyoDoctestModule],
                classOf[KyoCompatModule]
            )

            assert(apiTypes.exists(_.getSimpleName == "KyoDoctestModule"))
        }

        test("public API exposes Kyo FFI module traits") {
            val apiTypes = Seq(
                classOf[KyoFfiModule],
                classOf[KyoFfiJSModule],
                classOf[KyoFfiNativeModule],
                classOf[KyoFfiWasmModule],
                classOf[KyoFfiSbtModule],
                classOf[KyoFfiSbtJSModule],
                classOf[KyoFfiSbtNativeModule],
                classOf[KyoFfiSbtWasmModule],
                classOf[KyoFfiTests]
            )

            assert(apiTypes.map(_.getSimpleName).contains("KyoFfiModule"))
            assert(apiTypes.map(_.getSimpleName).contains("KyoFfiSbtModule"))
        }

        test("FFI layout defaults separate Mill-native and sbt-compatible source roots") {
            val millRoots = FfiDefaults.millCSourceRoots.map(_.toString)
            val sbtRoots  = FfiDefaults.sbtCSourceRoots.map(_.toString)

            assert(millRoots == Seq("src/c"))
            assert(sbtRoots == Seq("src/main/c"))
            assert(millRoots != sbtRoots)
        }

        test("default FFI settings match Kyo plugin defaults") {
            assert(FfiDefaults.libraryId == "kyo_ffi")
            assert(FfiDefaults.cCompiler(sys.env - "CC") == "cc")
            assert(FfiDefaults.cFlags == Seq("-O2", "-fPIC", "-Wall"))
            assert(FfiDefaults.scratchSize == 64 * 1024)
            assert(FfiDefaults.systemLibraries.contains("c"))
            assert(FfiDefaults.systemLibraries.contains("m"))
        }

        test("WASM FFI fails with clear unsupported-platform message") {
            val ex = thrown[UnsupportedOperationException] {
                throw FfiTargetPlatform.Wasm.unsupported()
            }
            assert(ex.getMessage.contains("Kyo FFI is not supported on Scala.js WASM"))
        }
    }
