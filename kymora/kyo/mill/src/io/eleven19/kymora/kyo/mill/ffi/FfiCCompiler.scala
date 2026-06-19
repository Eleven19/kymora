package io.eleven19.kymora.kyo.mill.ffi

import scala.sys.process.Process

/** C compiler command construction and execution for Kyo FFI. */
object FfiCCompiler:

    enum Family derives CanEqual:
        case Gcc, Clang, Msvc, ZigCc

    def detectFamily(cc: String): Family =
        val lower = cc.toLowerCase
        if lower.contains("cl.exe") || lower.endsWith("/cl") || lower.endsWith("\\cl") || lower == "cl" then Family.Msvc
        else if lower.contains("zig") then Family.ZigCc
        else if lower.contains("clang") then Family.Clang
        else Family.Gcc

    def detectOs(): String =
        detectOsWith(sys.props("os.name"), path => os.exists(os.Path(path)))

    def detectOsWith(osName: String, fileExists: String => Boolean): String =
        val name = osName.toLowerCase
        if name.contains("mac") then "darwin"
        else if name.contains("linux") then
            if fileExists("/lib/ld-musl-x86_64.so.1") || fileExists("/lib/ld-musl-aarch64.so.1") then "linux-musl"
            else "linux"
        else if name.contains("windows") then "windows"
        else throw new IllegalArgumentException(s"Unsupported OS: $name")

    def detectArch(): String =
        normalizeArch(sys.props("os.arch"))

    def normalizeArch(arch: String): String = arch match
        case "amd64" | "x86_64"  => "x86_64"
        case "aarch64" | "arm64" => "aarch64"
        case other               => throw new IllegalArgumentException(s"Unsupported arch: $other")

    def foldedLinkLibFlags(linkLibs: Seq[String], staticLink: Boolean): Seq[String] =
        if staticLink && linkLibs.nonEmpty then
            Seq("-Wl,-Bstatic") ++ linkLibs.map(library => s"-l$library") ++ Seq("-Wl,-Bdynamic")
        else linkLibs.map(library => s"-l$library")

    def vendoredArchiveLinkFlags(
        libDirs: Seq[os.Path],
        linkLibs: Seq[String],
        staticLink: Boolean,
        osName: String
    ): Seq[String] =
        if linkLibs.isEmpty then Seq.empty
        else if staticLink && osName == "darwin" then
            linkLibs.map { library =>
                val archiveName = s"lib$library.a"
                libDirs
                    .map(_ / archiveName)
                    .find(os.exists)
                    .map(_.toString)
                    .getOrElse(libDirs.headOption.map(path => (path / archiveName).toString).getOrElse(archiveName))
            }
        else
            val searchFlags = libDirs.map(path => s"-L${path.toString}")
            searchFlags ++ foldedLinkLibFlags(linkLibs, staticLink)

    def vendoredArchiveForceLoadFlags(
        libDirs: Seq[os.Path],
        linkLibs: Seq[String],
        staticLink: Boolean,
        osName: String
    ): Seq[String] =
        if linkLibs.isEmpty || !staticLink then vendoredArchiveLinkFlags(libDirs, linkLibs, staticLink, osName)
        else if osName == "darwin" then
            linkLibs.map { library =>
                val archiveName = s"lib$library.a"
                val path = libDirs
                    .map(_ / archiveName)
                    .find(os.exists)
                    .map(_.toString)
                    .getOrElse(libDirs.headOption.map(path => (path / archiveName).toString).getOrElse(archiveName))
                s"-Wl,-force_load,$path"
            }
        else
            libDirs.map(path => s"-L${path.toString}") ++
                Seq("-Wl,--whole-archive") ++ linkLibs.map(library => s"-l$library") ++ Seq("-Wl,--no-whole-archive")

    def buildCommand(
        cc: String,
        family: Family,
        cFlags: Seq[String],
        linkFlags: Seq[String],
        linkLibs: Seq[String],
        sources: Seq[os.Path],
        includes: Seq[os.Path],
        outFile: os.Path,
        staticLink: Boolean,
        libDirs: Seq[os.Path] = Seq.empty,
        osName: String = ""
    ): Seq[String] =
        family match
            case Family.Msvc =>
                splitCc(cc) ++ Seq("/LD") ++ cFlags.flatMap(translateFlagMsvc) ++
                    (if staticLink then Seq("/MT") else Seq.empty) ++
                    includes.map(path => s"/I${path.toString}") ++
                    sources.map(_.toString) ++
                    Seq(s"/Fe:${outFile.toString}") ++
                    linkFlags ++ libDirs.map(path => s"/LIBPATH:${path.toString}") ++ linkLibs.map(library =>
                        s"$library.lib"
                    )
            case _ =>
                val includeFlags = includes.flatMap(path => Seq("-I", path.toString))
                val linkLibFlags =
                    if libDirs.nonEmpty then vendoredArchiveLinkFlags(libDirs, linkLibs, staticLink, osName)
                    else foldedLinkLibFlags(linkLibs, staticLink)
                splitCc(cc) ++ Seq("-shared") ++ cFlags ++ includeFlags ++ sources.map(_.toString) ++
                    Seq("-o", outFile.toString) ++ linkLibFlags ++ linkFlags

    def translateFlagMsvc(flag: String): Seq[String] = flag match
        case "-shared"                               => Seq("/LD")
        case "-fPIC"                                 => Seq.empty
        case "-O0"                                   => Seq("/Od")
        case "-O1"                                   => Seq("/O1")
        case "-O2" | "-O3"                           => Seq("/O2")
        case "-Wall"                                 => Seq("/W3")
        case "-Wextra"                               => Seq("/W4")
        case f if f.startsWith("-I") && f.length > 2 => Seq("/I" + f.drop(2))
        case f if f.startsWith("-l") && f.length > 2 => Seq(f.drop(2) + ".lib")
        case other                                   => Seq(other)

    def compile(
        cc: String,
        cFlags: Seq[String],
        linkFlags: Seq[String],
        linkLibs: Seq[String],
        sources: Seq[os.Path],
        libraryId: String,
        outputDir: os.Path,
        log: String => Unit,
        includes: Seq[os.Path] = Seq.empty,
        staticLink: Boolean = false,
        libDirs: Seq[os.Path] = Seq.empty
    ): Seq[os.Path] =
        val osName = detectOs()
        val arch   = detectArch()
        val ext = osName match
            case "linux" | "linux-musl" => "so"
            case "darwin"               => "dylib"
            case "windows"              => "dll"
            case other => throw new IllegalArgumentException(s"Unsupported OS for C compilation: $other")
        val prefix  = if osName == "windows" then "" else "lib"
        val outFile = outputDir / s"$prefix$libraryId-$osName-$arch.$ext"
        os.makeDir.all(outputDir)
        val command = buildCommand(
            cc = cc,
            family = detectFamily(cc),
            cFlags = cFlags,
            linkFlags = linkFlags,
            linkLibs = linkLibs,
            sources = sources,
            includes = includes,
            outFile = outFile,
            staticLink = staticLink,
            libDirs = libDirs,
            osName = osName
        )
        log(s"[kymora-kyo-mill] ${command.mkString(" ")}")
        val exitCode = Process(command).!
        if exitCode != 0 then throw new RuntimeException(s"C compilation failed (exit=$exitCode)")
        Seq(outFile)

    private def splitCc(cc: String): Seq[String] =
        val trimmed = cc.trim
        if trimmed.isEmpty then Seq("cc")
        else trimmed.split("\\s+").toSeq
