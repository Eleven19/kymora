package io.eleven19.kymora.kyo.mill.ffi

import scala.reflect.ClassTag
import utest.*

object FfiPackagerTests extends TestSuite:

    private def thrown[A <: Throwable](f: => Any)(using ClassTag[A]): A =
        try
            val _ = f
            throw new java.lang.AssertionError("Expected exception")
        catch
            case e: A => e

    def tests: Tests = Tests {
        test("JVM packaging copies to META-INF/native and strips platform suffix") {
            val root = os.temp.dir(prefix = "kymora-ffi-packager-")
            val src  = root / "libdemo-linux-x86_64.so"
            os.write(src, "demo")

            val copied = FfiPackager.copyForPlatform(
                platform = FfiTargetPlatform.Jvm,
                artifacts = Seq(src),
                resDir = root / "resources",
                libraryId = "demo",
                osName = "linux",
                arch = "x86_64"
            )

            assert(copied.map(_.toString) == Seq((root / "resources" / "META-INF" / "native" / "linux-x86_64" / "libdemo.so").toString))
            assert(os.read(copied.head) == "demo")
        }

        test("JS packaging copies to kyo-ffi/native and strips platform suffix") {
            val root = os.temp.dir(prefix = "kymora-ffi-packager-")
            val src  = root / "libdemo-linux-x86_64.so"
            os.write(src, "demo")

            val copied = FfiPackager.copyForPlatform(
                platform = FfiTargetPlatform.Js,
                artifacts = Seq(src),
                resDir = root / "resources",
                libraryId = "demo",
                osName = "linux",
                arch = "x86_64"
            )

            assert(copied.map(_.toString) == Seq((root / "resources" / "kyo-ffi" / "native" / "linux-x86_64" / "libdemo.so").toString))
            assert(os.read(copied.head) == "demo")
        }

        test("Native packaging is a no-op") {
            val copied = FfiPackager.copyForPlatform(
                platform = FfiTargetPlatform.Native,
                artifacts = Seq(os.root / "missing.so"),
                resDir = os.temp.dir(prefix = "kymora-ffi-packager-"),
                libraryId = "demo",
                osName = "linux",
                arch = "x86_64"
            )

            assert(copied == Seq.empty)
        }

        test("missing artifacts fail") {
            val ex = thrown[java.nio.file.NoSuchFileException] {
                FfiPackager.copyForPlatform(
                    platform = FfiTargetPlatform.Jvm,
                    artifacts = Seq(os.root / "missing.so"),
                    resDir = os.temp.dir(prefix = "kymora-ffi-packager-"),
                    libraryId = "demo",
                    osName = "linux",
                    arch = "x86_64"
                )
            }
            assert(ex.getMessage.contains("missing.so"))
        }
    }
