package io.eleven19.kymora.kyo.mill

import mill.testkit.{ExampleTester, IntegrationTester}
import utest.*

object KyoMillIntegrationTests extends TestSuite:

    private def resourceRoot: os.Path =
        os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))

    private def resource(name: String): os.Path =
        resourceRoot / name

    private def tester(name: String): IntegrationTester =
        IntegrationTester(
            daemonMode = false,
            workspaceSourcePath = resource(name),
            millExecutable = millExecutable,
            cleanupProcessIdFile = false
        )

    private def millExecutable: os.Path =
        resourceRoot / os.up / os.up / os.up / os.up / os.up / "mill"

    private def npmInstall(t: IntegrationTester): Unit =
        val result = os.proc("npm", "install", "--silent").call(cwd = t.workspacePath, check = false)
        assert(result.exitCode == 0)

    private def compiledNativeLibrary(t: IntegrationTester, libraryId: String): os.Path =
        val compile = t.eval("app.ffiCompile")
        assert(compile.isSuccess)
        val nativeDir = t.workspacePath / "out" / "app" / "ffiCompile.dest" / "native"
        os.walk(nativeDir)
            .find(path => os.isFile(path) && path.last.contains(libraryId))
            .getOrElse(throw new java.lang.AssertionError(s"Compiled native library for $libraryId not found in $nativeDir"))

    def tests: Tests = Tests {
        test("scenario kyo-test-jvm: downstream module mixes KyoTestModule and runs a Kyo test") {
            val t = tester("kyo-test-jvm")
            try
                val result = t.eval("app.test")
                assert(result.isSuccess)
                assert(result.out.contains("1 tests"))
            finally t.close()
        }

        test("scenario wasm-platform: downstream module exposes Wasm suffix and linker settings") {
            val t = tester("wasm-platform")
            try
                val suffix = t.eval(Seq("show", "app.platformSuffix"))
                assert(suffix.isSuccess)
                assert(suffix.out.contains("_sjs1-wasm"))

                val moduleKind = t.eval(Seq("show", "app.moduleKind"))
                assert(moduleKind.isSuccess)
                assert(moduleKind.out.contains("ESModule"))

                val useWasm = t.eval(Seq("show", "app.scalaJSExperimentalUseWebAssembly"))
                assert(useWasm.isSuccess)
                assert(useWasm.out.contains("true"))
            finally t.close()
        }

        test("scenario doctest-basic: downstream module validates README scala blocks") {
            val t = tester("doctest-basic")
            try
                val result = t.eval("app.doctest")
                assert(result.isSuccess)
                assert(result.err.contains("doctest: total=1"))
            finally t.close()
        }

        test("scenario compat-supported: downstream module declares a supported compat axis") {
            val t = tester("compat-supported")
            try
                val result = t.eval(Seq("show", "app.kyoCompatArtifactName"))
                assert(result.isSuccess)
                assert(result.out.contains("kyo-compat-zio"))
            finally t.close()
        }

        test("scenario compat-unsupported: downstream module fails clearly for an unsupported axis") {
            val t = tester("compat-unsupported")
            try
                val result = t.eval(Seq("show", "app.compatAxis"))
                assert(!result.isSuccess)
                assert(result.err.contains("Unsupported Kyo compat axis"))
            finally t.close()
        }

        test("scenario ffi-dump-command: downstream module exposes realistic C compiler command") {
            val t = tester("ffi-dump-command")
            try
                val result = t.eval(Seq("show", "app.ffiDumpCcCommand"))
                assert(result.isSuccess)
                assert(result.out.contains("math.c"))
                assert(result.out.contains("-I"))
                assert(result.out.contains("libmath-"))
            finally t.close()
        }

        test("scenario ffi-npm-template: JS module writes a koffi package template explicitly") {
            val t = tester("ffi-npm-template")
            try
                val result = t.eval("app.ffiNpmBundleTemplate")
                assert(result.isSuccess)
                val packageJson = t.workspacePath / "app" / "package.json"
                assert(os.read(packageJson).contains("\"koffi\": \"^2.7\""))
            finally
                t.close()
        }

        test("scenario ffi-wasm-unsupported: WASM FFI fails with a clear message") {
            val t = tester("ffi-wasm-unsupported")
            try
                val result = t.eval("app.ffiGenerate")
                assert(!result.isSuccess)
                assert(result.err.contains("Kyo FFI is not supported on Scala.js WASM"))
            finally t.close()
        }

        test("scenario ffi-jvm-math: generated JVM binding calls a bundled C add function") {
            val t = tester("ffi-jvm-math")
            try
                val result = t.eval("app.test")
                assert(result.isSuccess)
                assert(result.out.contains("generated JVM binding calls"))
            finally t.close()
        }

        test("scenario ffi-mill-layout: default FFI module discovers Mill-native source roots") {
            val t = tester("ffi-mill-layout")
            try
                val result = t.eval("app.test")
                assert(result.isSuccess)
                assert(result.out.contains("Mill-native FFI defaults discover"))
            finally t.close()
        }

        test("scenario ffi-sbt-layout: explicit sbt FFI module discovers sbt-compatible source roots") {
            val t = tester("ffi-sbt-layout")
            try
                val result = t.eval("app.test")
                assert(result.isSuccess)
                assert(result.out.contains("sbt-compatible FFI traits discover"))
            finally t.close()
        }

        test("scenario ffi-primitives-and-strings: vendored C covers scalar and string bindings") {
            val t = tester("ffi-primitives-and-strings")
            try
                val result = t.eval("app.test")
                assert(result.isSuccess)
                assert(result.out.contains("primitive numbers, booleans, and strings"))
            finally t.close()
        }

        test("example ffi-string-library: realistic text utility library uses String bindings") {
            val t = tester("ffi-string-library")
            try
                val result = t.eval("app.test")
                assert(result.isSuccess)
                assert(result.out.contains("realistic text values through Kyo FFI"))
            finally t.close()
        }

        test("example ffi-struct-library: realistic contact library uses nested structs and string results") {
            val t = tester("ffi-struct-library")
            try
                val result = t.eval("app.test")
                assert(result.isSuccess)
                assert(result.out.contains("nested structs, string parameters, and string return fields"))
            finally t.close()
        }

        test("scenario ffi-buffers: vendored C reads and mutates Kyo FFI buffers") {
            val t = tester("ffi-buffers")
            try
                val result = t.eval("app.test")
                assert(result.isSuccess)
                assert(result.out.contains("read and mutate a Kyo FFI Buffer"))
            finally t.close()
        }

        test("scenario ffi-structs: vendored C covers structs and multi-value returns") {
            val t = tester("ffi-structs")
            try
                val result = t.eval("app.test")
                assert(result.isSuccess)
                assert(result.out.contains("nested structs, packed structs, multi-value returns"))
            finally t.close()
        }

        test("scenario ffi-callbacks: vendored C covers transient and retained callbacks") {
            val t = tester("ffi-callbacks")
            try
                val result = t.eval("app.test")
                assert(result.isSuccess)
                assert(result.out.contains("transient callbacks, buffer callbacks, and retained callbacks"))
            finally t.close()
        }

        test("scenario ffi-js-vendored: Scala.js FFI loads vendored C through koffi") {
            val t = tester("ffi-js-vendored")
            try
                npmInstall(t)
                val library = compiledNativeLibrary(t, "jsvendored")
                val result = t.eval("app.test", env = Map("KYO_FFI_JSVENDORED_PATH" -> library.toString))
                assert(result.isSuccess)
                assert(result.out.contains("loads a vendored C library through koffi"))
            finally t.close()
        }

        test("scenario ffi-native-vendored: Scala Native FFI links vendored C resources") {
            val t = tester("ffi-native-vendored")
            try
                val result = t.eval("app.test")
                assert(result.isSuccess)
                assert(result.out.contains("links a vendored C source copied into scala-native resources"))
            finally t.close()
        }

        test("example kyo-test usage is runnable documentation") {
            ExampleTester.run(
                daemonMode = false,
                workspaceSourcePath = resource("kyo-test-example"),
                millExecutable = millExecutable
            )
        }
    }
