package io.eleven19.kymora.kyo.mill.ffi

import utest.*

object FfiNpmBundleTemplateTests extends TestSuite:

    def tests: Tests = Tests {
        test("writes minimal package json with Kyo-supported koffi range") {
            val dir = os.temp.dir(prefix = "kymora-ffi-npm-")
            val out = dir / "package.json"

            val _ = FfiNpmBundleTemplate.write(out, packageName = "demo")

            val json = ujson.read(os.read(out))
            assert(json("name").str == "demo")
            assert(json("private").bool)
            assert(json("dependencies")("koffi").str == "^2.7")
        }

        test("escapes package names through structured JSON rendering") {
            val json = ujson.read(FfiNpmBundleTemplate.packageJson("demo\"quoted"))

            assert(json("name").str == "demo\"quoted")
            assert(json("dependencies")("koffi").str == "^2.7")
        }

        test("does not overwrite existing package json by default") {
            val dir = os.temp.dir(prefix = "kymora-ffi-npm-")
            val out = dir / "package.json"
            os.write(out, """{"custom":true}""")

            val _ = FfiNpmBundleTemplate.write(out, packageName = "demo")

            assert(os.read(out) == """{"custom":true}""")
        }
    }
