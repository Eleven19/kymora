package io.eleven19.kymora.kyo.mill.ffi

/** Copies compiled native artifacts into Kyo FFI runtime resource layouts. */
object FfiPackager:

    def copyForPlatform(
        platform: FfiTargetPlatform,
        artifacts: Seq[os.Path],
        resDir: os.Path,
        libraryId: String,
        osName: String = FfiCCompiler.detectOs(),
        arch: String = FfiCCompiler.detectArch()
    ): Seq[os.Path] =
        platform match
            case FfiTargetPlatform.Native => Seq.empty
            case FfiTargetPlatform.Wasm   => throw platform.unsupported()
            case FfiTargetPlatform.Jvm =>
                copyArtifacts(artifacts, resDir / "META-INF" / "native" / s"$osName-$arch", osName, arch)
            case FfiTargetPlatform.Js =>
                copyArtifacts(artifacts, resDir / "kyo-ffi" / "native" / s"$osName-$arch", osName, arch)

    def copyForPlatformMulti(
        platform: FfiTargetPlatform,
        libraries: Seq[(String, Seq[os.Path])],
        resDir: os.Path,
        osName: String = FfiCCompiler.detectOs(),
        arch: String = FfiCCompiler.detectArch()
    ): Seq[os.Path] =
        libraries.flatMap { case (_, artifacts) =>
            copyForPlatform(platform, artifacts, resDir, libraryId = "", osName = osName, arch = arch)
        }

    def canonicalName(name: String, os: String, arch: String): String =
        val dot = name.lastIndexOf('.')
        if dot < 0 then name
        else
            val ext        = name.substring(dot)
            val dropSuffix = s"-$os-$arch$ext"
            if name.endsWith(dropSuffix) then name.substring(0, name.length - dropSuffix.length) + ext
            else name

    private def copyArtifacts(artifacts: Seq[os.Path], destDir: os.Path, osName: String, arch: String): Seq[os.Path] =
        os.makeDir.all(destDir)
        artifacts.map { artifact =>
            val dest = destDir / canonicalName(artifact.last, osName, arch)
            os.copy.over(artifact, dest, createFolders = true)
            dest
        }
