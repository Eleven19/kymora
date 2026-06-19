package io.eleven19.kymora.kyo.mill.ffi

import _root_.mill.*
import _root_.mill.scalajslib.*
import _root_.mill.scalalib.*
import _root_.mill.scalanativelib.*
import io.eleven19.kymora.kyo.mill.KyoMillDefaults

private[kyo] trait KyoFfiBase extends ScalaModule:
    def kyoVersion: T[String] = Task(KyoMillDefaults.kyoVersion)

    def ffiTargetPlatform: T[FfiTargetPlatform] = Task(FfiTargetPlatform.Jvm)

    def ffiLibraryId: T[String] = Task(FfiDefaults.libraryId)

    def ffiCSourceDir: T[os.Path] = Task {
        ffiCSourceRoots().headOption.map(_.path).getOrElse(moduleDir / "src" / "c")
    }

    def ffiCSourceRoots: T[Seq[PathRef]]

    def ffiCSources: T[Seq[os.Path]] = Task {
        ffiCSourceRoots().flatMap { root =>
            if os.exists(root.path) then os.walk(root.path).filter(path => os.isFile(path) && path.last.endsWith(".c"))
            else Seq.empty
        }
    }

    def ffiCHeaders: T[Seq[os.Path]] = Task {
        ffiCSourceRoots().flatMap { root =>
            if os.exists(root.path) then os.walk(root.path).filter(path => os.isFile(path) && path.last.endsWith(".h"))
            else Seq.empty
        }
    }

    def ffiIncludes: T[Seq[os.Path]] = Task {
        ffiCSourceRoots().map(_.path).filter(os.exists)
    }

    def ffiLinkLibs: T[Seq[String]]       = Task(Seq.empty)
    def ffiCCompiler: T[String]           = Task(FfiDefaults.cCompiler(Task.env))
    def ffiCFlags: T[Seq[String]]         = Task(FfiDefaults.cFlags)
    def ffiLinkFlags: T[Seq[String]]      = Task(Seq.empty)
    def ffiStaticLink: T[Boolean]         = Task(false)
    def ffiScratchSize: T[Int]            = Task(FfiDefaults.scratchSize)
    def ffiExtractDir: T[Option[os.Path]] = Task(None)
    def ffiStrictBlocking: T[Boolean]     = Task(false)
    def ffiStrictCallbacks: T[Boolean]    = Task(false)

    def ffiStrictDiscovery: T[Boolean] = Task {
        sys.props.get("kyo.ffi.strictDiscovery").contains("true")
    }
    def ffiSystemLibraries: T[Seq[String]] = Task(FfiDefaults.systemLibraries)

    def ffiLibraries: T[Seq[FfiLibrary]] = Task(Seq.empty)

    def ffiLibrariesResolved: T[Seq[FfiLibrary]] = Task {
        val multi = ffiLibraries()
        val raw =
            if multi.nonEmpty then multi
            else
                Seq(
                    FfiLibrary(
                        id = ffiLibraryId(),
                        cSources = ffiCSources(),
                        cHeaders = ffiCHeaders(),
                        linkLibs = ffiLinkLibs(),
                        staticLink = ffiStaticLink()
                    )
                )
        FfiLibrary.sort(raw)
    }

    def ffiCodegenMvnDeps: T[Seq[Dep]] = Task {
        Seq(
            mvn"io.getkyo:kyo-ffi-codegen_3:${kyoVersion()}",
            mvn"org.scala-lang:scala3-compiler_3:${scalaVersion()}"
        )
    }

    def ffiToolClasspath: T[Seq[PathRef]] = Task {
        defaultResolver().classpath(ffiCodegenMvnDeps())
    }

    def ffiRuntimeJavaOptions: T[Seq[String]] = Task {
        ffiExtractDir().map(path => s"-Dkyo.ffi.tmpdir=$path").toSeq ++
            Seq(s"-Dkyo.ffi.scratch.size=${ffiScratchSize()}")
    }

    override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
        super.mandatoryMvnDeps() ++ Seq(mvn"io.getkyo::kyo-ffi::${kyoVersion()}")
    }

    override def forkArgs: T[Seq[String]] = Task {
        super.forkArgs() ++ ffiRuntimeJavaOptions()
    }

    def ffiGeneratedDir: T[os.Path] = Task {
        Task.dest / "src"
    }

    def ffiNativeResourceDir: T[os.Path] = Task {
        Task.dest / "resources" / "scala-native"
    }

    def ffiCompiledDir: T[os.Path] = Task {
        Task.dest / "native"
    }

    def ffiPackagedResourcesDir: T[os.Path] = Task {
        Task.dest / "resources"
    }

    def ffiGenerate: T[Seq[PathRef]] = Task {
        val platform = ffiTargetPlatform()
        if platform == FfiTargetPlatform.Wasm then throw platform.unsupported()
        val outputDir = Task.dest / "src"
        os.makeDir.all(outputDir)

        val userSources = sourceFilesFrom(sources()).filter(_.last.endsWith(".scala"))
        val toolCp      = ffiToolClasspath().map(_.path)
        val userCp      = compileClasspath().map(_.path)
        val tastyDir    = Task.dest / "tasty"
        val tastyFiles = FfiCodegenBridge.compileSourcesToTasty(
            sources = userSources,
            classpath = (toolCp ++ userCp).distinct,
            outputDir = tastyDir,
            log = Task.log.warn(_)
        )

        if tastyFiles.isEmpty then
            if ffiStrictDiscovery() then
                throw new IllegalStateException("No FFI traits discovered while generating Kyo FFI bindings")
            Seq(PathRef(outputDir))
        else
            val generated = FfiCodegenBridge.generate(
                tastyFiles = tastyFiles,
                userClasspath = userCp,
                toolClasspath = toolCp,
                outputDir = outputDir,
                platform = platform,
                libraryId = Some(ffiLibraryId()),
                libraries = ffiLibrariesResolved(),
                strictBlocking = ffiStrictBlocking(),
                strictCallbacks = ffiStrictCallbacks(),
                includeDirs = ffiIncludes() ++ ffiLibrariesResolved().flatMap(library =>
                    library.cHeaders.map(parentDir) ++ library.includeDirs
                ),
                warn = Task.log.warn(_)
            )
            validateGeneratedLibraries(generated.traits, ffiLibrariesResolved(), ffiSystemLibraries().toSet)
            removeStaleGeneratedImpls(outputDir, generated.traits)
            Seq(PathRef(outputDir))
    }

    override def generatedSources: T[Seq[PathRef]] = Task {
        super.generatedSources() ++ ffiGenerate()
    }

    def ffiDumpCcCommand(): Command[Seq[Seq[String]]] = Task.Command {
        ffiBuildCommands()
    }

    def ffiCompile: T[Seq[PathRef]] = Task {
        val platform = ffiTargetPlatform()
        platform match
            case FfiTargetPlatform.Native =>
                Seq.empty
            case FfiTargetPlatform.Wasm =>
                throw platform.unsupported()
            case FfiTargetPlatform.Jvm | FfiTargetPlatform.Js =>
                val outputDir = Task.dest / "native"
                os.makeDir.all(outputDir)
                val buildOs = FfiCCompiler.detectOs()
                ffiLibrariesResolved().flatMap { library =>
                    if library.cSources.isEmpty then
                        Task.log.info(
                            s"[kymora-kyo-mill] ffiCompile: no C sources declared for ${library.id}; skipping."
                        )
                        Seq.empty
                    else
                        val headerDirs = library.cHeaders.map(parentDir).distinct
                        val includes   = (ffiIncludes() ++ headerDirs ++ library.includeDirs).distinct
                        val files = FfiCCompiler.compile(
                            cc = ffiCCompiler(),
                            cFlags = ffiCFlags() ++ library.cFlags,
                            linkFlags = ffiLinkFlags() ++ library.linkFlags,
                            linkLibs = library.resolvedLinkLibs(buildOs),
                            sources = library.cSources,
                            libraryId = library.id,
                            outputDir = outputDir,
                            log = Task.log.info(_),
                            includes = includes,
                            staticLink = library.staticLink,
                            libDirs = library.libDirs
                        )
                        files.map(PathRef(_))
                }
    }

    def ffiPackage: T[Seq[PathRef]] = Task {
        val platform  = ffiTargetPlatform()
        val artifacts = ffiCompile().map(_.path)
        if artifacts.isEmpty then Seq.empty
        else
            val outputDir = Task.dest / "resources"
            val grouped   = groupArtifactsByLibrary(artifacts, ffiLibrariesResolved())
            val _         = FfiPackager.copyForPlatformMulti(platform, grouped, outputDir)
            Seq(PathRef(outputDir))
    }

    override def resources: T[Seq[PathRef]] = Task {
        super.resources() ++ ffiPackage() ++ ffiNativeResources()
    }

    def ffiNativeResources: T[Seq[PathRef]] = Task {
        if ffiTargetPlatform() != FfiTargetPlatform.Native then Seq.empty
        else
            val _       = ffiCSourceRoots()
            val rootDir = Task.dest / "resources"
            val destDir = rootDir / "scala-native"
            os.makeDir.all(destDir)
            ffiLibrariesResolved().flatMap(_.cSources).distinct.foreach { source =>
                val dest = destDir / source.last
                if !os.exists(dest) || os.read.bytes(dest).toSeq != os.read.bytes(source).toSeq then
                    os.copy.over(source, dest, createFolders = true)
            }
            Seq(PathRef(rootDir))
    }

    def ffiNativeLinkingOptions: T[Seq[String]] = Task {
        if ffiTargetPlatform() != FfiTargetPlatform.Native then Seq.empty
        else
            val buildOs = FfiCCompiler.detectOs()
            ffiLibrariesResolved().flatMap { library =>
                if library.libDirs.nonEmpty then
                    FfiCCompiler.vendoredArchiveForceLoadFlags(
                        library.libDirs.distinct,
                        library.resolvedLinkLibs(buildOs),
                        library.staticLink,
                        buildOs
                    ) ++ library.linkFlags
                else FfiCCompiler.foldedLinkLibFlags(library.resolvedLinkLibs(buildOs), library.staticLink)
            }
    }

    def ffiNpmBundleTemplate(): Command[PathRef] = Task.Command {
        PathRef(FfiNpmBundleTemplate.write(moduleDir / "package.json", ffiLibraryId()))
    }

    def ffiClean(): Command[Unit] = Task.Command {
        os.remove.all(ffiGeneratedDir())
        os.remove.all(ffiCompiledDir())
        os.remove.all(ffiPackagedResourcesDir())
        os.remove.all(ffiNativeResourceDir())
    }

    def ffiBuildCommands: T[Seq[Seq[String]]] = Task {
        val targetDir = ffiCompiledDir()
        val family    = FfiCCompiler.detectFamily(ffiCCompiler())
        val buildOs   = FfiCCompiler.detectOs()
        val arch      = FfiCCompiler.detectArch()
        val ext = buildOs match
            case "linux" | "linux-musl" => "so"
            case "darwin"               => "dylib"
            case "windows"              => "dll"
            case other                  => throw new IllegalArgumentException(s"Unsupported OS: $other")
        ffiLibrariesResolved().map { library =>
            val headerDirs = library.cHeaders.map(parentDir).distinct
            val includes   = (ffiIncludes() ++ headerDirs ++ library.includeDirs).distinct
            val prefix     = if buildOs == "windows" then "" else "lib"
            val outFile    = targetDir / s"$prefix${library.id}-$buildOs-$arch.$ext"
            FfiCCompiler.buildCommand(
                cc = ffiCCompiler(),
                family = family,
                cFlags = ffiCFlags() ++ library.cFlags,
                linkFlags = ffiLinkFlags() ++ library.linkFlags,
                linkLibs = library.resolvedLinkLibs(buildOs),
                sources = library.cSources,
                includes = includes,
                outFile = outFile,
                staticLink = library.staticLink,
                libDirs = library.libDirs.distinct,
                osName = buildOs
            )
        }
    }

    private def sourceFilesFrom(sourceRoots: Seq[PathRef]): Seq[os.Path] =
        sourceRoots.flatMap { ref =>
            if os.exists(ref.path) then os.walk(ref.path).filter(os.isFile)
            else Seq.empty
        }

    private def parentDir(path: os.Path): os.Path =
        path / os.up

    private def validateGeneratedLibraries(
        traits: Seq[FfiCodegenBridge.TraitInfo],
        libraries: Seq[FfiLibrary],
        systemLibraries: Set[String]
    ): Unit =
        val declared = libraries.map(_.id).toSet
        val offenders =
            traits.filter(info => !declared.contains(info.library) && !systemLibraries.contains(info.library))
        if offenders.nonEmpty then
            val message = offenders
                .map(info => s"${info.fqcn} declares library = \"${info.library}\"")
                .mkString("Kyo FFI library-id validation failed: ", "; ", "")
            throw new IllegalArgumentException(message)

    private def removeStaleGeneratedImpls(outputDir: os.Path, traits: Seq[FfiCodegenBridge.TraitInfo]): Unit =
        val expected = traits.map { info =>
            val packageDir =
                if info.packageName.isEmpty then outputDir
                else info.packageName.split('.').foldLeft(outputDir)((dir, segment) => dir / segment)
            packageDir / s"${info.simpleName}Impl.scala"
        }.toSet
        if os.exists(outputDir) then
            os.walk(outputDir)
                .filter(path => os.isFile(path) && path.last.endsWith("Impl.scala"))
                .filterNot(expected.contains)
                .foreach(os.remove)

    private def groupArtifactsByLibrary(
        artifacts: Seq[os.Path],
        libraries: Seq[FfiLibrary]
    ): Seq[(String, Seq[os.Path])] =
        if libraries.size == 1 then Seq(libraries.head.id -> artifacts)
        else
            libraries.map { library =>
                val posix = s"lib${library.id}-"
                val win   = s"${library.id}-"
                library.id -> artifacts.filter(path => path.last.startsWith(posix) || path.last.startsWith(win))
            }

/** Mill-native Kyo FFI integration for JVM modules. */
trait KyoFfiModule extends ScalaModule with KyoFfiBase:
    def ffiCSourceRoots: T[Seq[PathRef]] = Task.Sources(FfiDefaults.millCSourceRoots*)

/** Mill-native Kyo FFI integration for Scala.js modules. */
trait KyoFfiJSModule extends ScalaJSModule with KyoFfiBase:
    override def ffiTargetPlatform: T[FfiTargetPlatform] = Task(FfiTargetPlatform.Js)
    def ffiCSourceRoots: T[Seq[PathRef]]                 = Task.Sources(FfiDefaults.millCSourceRoots*)

    override def moduleKind: T[_root_.mill.scalajslib.api.ModuleKind] =
        Task(_root_.mill.scalajslib.api.ModuleKind.CommonJSModule)

/** Mill-native Kyo FFI integration for Scala Native modules. */
trait KyoFfiNativeModule extends ScalaNativeModule with KyoFfiBase:
    override def ffiTargetPlatform: T[FfiTargetPlatform] = Task(FfiTargetPlatform.Native)
    def ffiCSourceRoots: T[Seq[PathRef]]                 = Task.Sources(FfiDefaults.millCSourceRoots*)

    override def nativeLinkingOptions: T[Seq[String]] = Task {
        super.nativeLinkingOptions() ++ ffiNativeLinkingOptions()
    }

/** Explicit unsupported marker for Scala.js Wasm FFI modules. */
trait KyoFfiWasmModule extends KyoFfiModule:
    override def ffiTargetPlatform: T[FfiTargetPlatform] = Task(FfiTargetPlatform.Wasm)

/** Kyo FFI integration for JVM modules using sbt/Maven-compatible source layout. */
trait KyoFfiSbtModule extends SbtModule with KyoFfiBase:
    override def ffiCSourceRoots: T[Seq[PathRef]] = Task.Sources(FfiDefaults.sbtCSourceRoots*)

/** Kyo FFI integration for Scala.js modules using sbt/Maven-compatible source layout. */
trait KyoFfiSbtJSModule extends ScalaJSModule with SbtModule with KyoFfiBase:
    override def ffiTargetPlatform: T[FfiTargetPlatform] = Task(FfiTargetPlatform.Js)
    override def ffiCSourceRoots: T[Seq[PathRef]]        = Task.Sources(FfiDefaults.sbtCSourceRoots*)

    override def moduleKind: T[_root_.mill.scalajslib.api.ModuleKind] =
        Task(_root_.mill.scalajslib.api.ModuleKind.CommonJSModule)

/** Kyo FFI integration for Scala Native modules using sbt/Maven-compatible source layout. */
trait KyoFfiSbtNativeModule extends SbtNativeModule with KyoFfiBase:
    override def ffiTargetPlatform: T[FfiTargetPlatform] = Task(FfiTargetPlatform.Native)
    override def ffiCSourceRoots: T[Seq[PathRef]]        = Task.Sources(FfiDefaults.sbtCSourceRoots*)

    override def nativeLinkingOptions: T[Seq[String]] = Task {
        super.nativeLinkingOptions() ++ ffiNativeLinkingOptions()
    }

/** Explicit unsupported marker for Scala.js Wasm FFI modules using sbt/Maven-compatible source layout. */
trait KyoFfiSbtWasmModule extends KyoFfiWasmModule with SbtModule:
    override def ffiCSourceRoots: T[Seq[PathRef]] = Task.Sources(FfiDefaults.sbtCSourceRoots*)

/** Test-module helper that propagates Kyo FFI JVM runtime options from an outer module. */
trait KyoFfiTests extends TestModule:
    def ffiRuntimeJavaOptions: T[Seq[String]] = Task(Seq.empty)

    override def forkArgs: T[Seq[String]] = Task {
        super.forkArgs() ++ ffiRuntimeJavaOptions()
    }
