package io.eleven19.kymora.kyo.mill.ffi

import utest.*

object FfiCCompilerTests extends TestSuite:

    def tests: Tests = Tests {
        test("detects compiler families") {
            assert(FfiCCompiler.detectFamily("gcc") == FfiCCompiler.Family.Gcc)
            assert(FfiCCompiler.detectFamily("clang") == FfiCCompiler.Family.Clang)
            assert(FfiCCompiler.detectFamily("zig cc") == FfiCCompiler.Family.ZigCc)
            assert(FfiCCompiler.detectFamily("cl.exe") == FfiCCompiler.Family.Msvc)
        }

        test("builds POSIX compiler command with includes, output, and link libraries") {
            val command = FfiCCompiler.buildCommand(
                cc = "gcc",
                family = FfiCCompiler.Family.Gcc,
                cFlags = Seq("-O2", "-fPIC", "-Wall"),
                linkFlags = Seq("-pthread"),
                linkLibs = Seq("m"),
                sources = Seq(os.root / "tmp" / "math.c"),
                includes = Seq(os.root / "tmp" / "include"),
                outFile = os.root / "tmp" / "libmath-linux-x86_64.so",
                staticLink = false
            )

            assert(command.containsSlice(Seq("-I", "/tmp/include")))
            assert(command.containsSlice(Seq("-o", "/tmp/libmath-linux-x86_64.so")))
            assert(command.contains("-lm"))
            assert(command.indexOf("-pthread") > command.indexOf("-lm"))
        }

        test("static linking folds only named libraries and avoids bare static") {
            val command = FfiCCompiler.buildCommand(
                cc = "gcc",
                family = FfiCCompiler.Family.Gcc,
                cFlags = Nil,
                linkFlags = Nil,
                linkLibs = Seq("uring"),
                sources = Seq(os.root / "tmp" / "uring.c"),
                includes = Nil,
                outFile = os.root / "tmp" / "liburing-linux-x86_64.so",
                staticLink = true
            )

            assert(command.containsSlice(Seq("-Wl,-Bstatic", "-luring", "-Wl,-Bdynamic")))
            assert(!command.contains("-static"))
        }

        test("vendored archives force-load on native links") {
            val linux = FfiCCompiler.vendoredArchiveForceLoadFlags(
                libDirs = Seq(os.root / "tmp" / "lib"),
                linkLibs = Seq("ssl", "crypto"),
                staticLink = true,
                osName = "linux"
            )

            assert(linux.contains("-Wl,--whole-archive"))
            assert(linux.contains("-lssl"))
            assert(linux.contains("-Wl,--no-whole-archive"))
        }

        test("detects OS and arch through test-visible helpers") {
            assert(FfiCCompiler.detectOsWith("Mac OS X", _ => false) == "darwin")
            assert(FfiCCompiler.detectOsWith("Linux", _ => false) == "linux")
            assert(FfiCCompiler.detectOsWith("Windows 11", _ => false) == "windows")
            assert(FfiCCompiler.normalizeArch("amd64") == "x86_64")
            assert(FfiCCompiler.normalizeArch("arm64") == "aarch64")
        }
    }
