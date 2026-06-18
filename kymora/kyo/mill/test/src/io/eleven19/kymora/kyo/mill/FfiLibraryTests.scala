package io.eleven19.kymora.kyo.mill.ffi

import utest.*

object FfiLibraryTests extends TestSuite:

    def tests: Tests = Tests {
        test("OS-specific link libraries are appended only for the matching OS") {
            val library = FfiLibrary(id = "demo", cSources = Nil, linkLibsByOs = Map("linux" -> Seq("uring")))

            assert(library.resolvedLinkLibs("linux") == Seq("uring"))
            assert(library.resolvedLinkLibs("linux-musl") == Seq("uring"))
            assert(library.resolvedLinkLibs("darwin") == Seq.empty)
        }

        test("always-on and OS-specific link libraries merge in stable deduplicated order") {
            val library = FfiLibrary(
                id = "demo",
                cSources = Nil,
                linkLibs = Seq("c", "uring"),
                linkLibsByOs = Map("linux" -> Seq("uring", "m"))
            )

            assert(library.resolvedLinkLibs("linux") == Seq("c", "uring", "m"))
        }
    }
