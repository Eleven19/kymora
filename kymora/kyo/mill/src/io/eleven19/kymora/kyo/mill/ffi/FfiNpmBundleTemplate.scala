package io.eleven19.kymora.kyo.mill.ffi

/** Writes the package.json needed by Node/koffi based Kyo FFI Scala.js modules. */
object FfiNpmBundleTemplate:
    val koffiSupportedRange: String = "^2.7"

    def packageJson(packageName: String): String =
        ujson.write(
            ujson.Obj(
                "name"    -> packageName,
                "private" -> true,
                "dependencies" -> ujson.Obj(
                    "koffi" -> koffiSupportedRange
                )
            ),
            indent = 2
        ) + "\n"

    def write(out: os.Path, packageName: String, overwrite: Boolean = false): os.Path =
        if !os.exists(out) || overwrite then os.write.over(out, packageJson(packageName), createFolders = true)
        out
