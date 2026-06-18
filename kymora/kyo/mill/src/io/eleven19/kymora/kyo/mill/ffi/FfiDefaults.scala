package io.eleven19.kymora.kyo.mill.ffi

/** Defaults shared by the Mill-native Kyo FFI integration. */
object FfiDefaults:
    val libraryId: String                 = "kyo_ffi"
    val cFlags: Seq[String]               = Seq("-O2", "-fPIC", "-Wall")
    val scratchSize: Int                  = 64 * 1024
    val millCSourceRoots: Seq[os.SubPath] = Seq(os.sub / "src" / "c")
    val sbtCSourceRoots: Seq[os.SubPath]  = Seq(os.sub / "src" / "main" / "c")

    val systemLibraries: Seq[String] = Seq(
        "c",
        "m",
        "pthread",
        "dl",
        "rt",
        "util",
        "crypt",
        "resolv",
        "nsl",
        "kernel32",
        "user32",
        "ws2_32",
        "advapi32"
    )

    def cCompiler(env: Map[String, String] = sys.env): String =
        env.getOrElse("CC", "cc")
