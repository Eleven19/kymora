package io.eleven19.kymora.kyo.mill.ffi

import scala.reflect.ClassTag
import utest.*

object FfiLibrarySortTests extends TestSuite:

    private def lib(id: String, deps: String*): FfiLibrary =
        FfiLibrary(id = id, cSources = Nil, dependsOn = deps)

    private def thrown[A <: Throwable](f: => Any)(using ClassTag[A]): A =
        try
            val _ = f
            throw new java.lang.AssertionError("Expected exception")
        catch
            case e: A => e

    def tests: Tests = Tests {
        test("independent libraries preserve input order") {
            val sorted = FfiLibrary.sort(Seq(lib("c"), lib("a"), lib("b"))).map(_.id)
            assert(sorted == Seq("c", "a", "b"))
        }

        test("dependencies are emitted before dependents") {
            val sorted = FfiLibrary.sort(Seq(lib("app", "core"), lib("core"))).map(_.id)
            assert(sorted == Seq("core", "app"))
        }

        test("duplicate library ids fail clearly") {
            val ex = thrown[IllegalArgumentException] {
                FfiLibrary.sort(Seq(lib("a"), lib("a")))
            }
            assert(ex.getMessage.contains("duplicate"))
            assert(ex.getMessage.contains("a"))
        }

        test("missing dependencies fail clearly") {
            val ex = thrown[IllegalArgumentException] {
                FfiLibrary.sort(Seq(lib("a", "missing")))
            }
            assert(ex.getMessage.contains("missing"))
            assert(ex.getMessage.contains("a"))
        }

        test("dependency cycles fail clearly") {
            val ex = thrown[IllegalArgumentException] {
                FfiLibrary.sort(Seq(lib("a", "b"), lib("b", "a")))
            }
            assert(ex.getMessage.contains("cycle"))
            assert(ex.getMessage.contains("a"))
            assert(ex.getMessage.contains("b"))
        }
    }
