package io.eleven19.kymora.kyo.mill.ffi

import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest

/** Reflection bridge from the Mill plugin runtime to Kyo's Scala 3 FFI codegen. */
object FfiCodegenBridge:

    final case class TraitInfo(fqcn: String, simpleName: String, packageName: String, library: String)
    final case class Generated(files: Seq[os.Path], traits: Seq[TraitInfo])

    def toolDependencyCoordinates(kyoVersion: String, scalaVersion: String): Seq[String] =
        Seq(
            s"io.getkyo:kyo-ffi-codegen_3:$kyoVersion",
            s"org.scala-lang:scala3-compiler_3:$scalaVersion"
        )

    def codegenFingerprint(toolClasspath: Seq[os.Path]): String =
        val md = MessageDigest.getInstance("SHA-256")
        toolClasspath.map(_.toString).sorted.foreach { pathString =>
            md.update(pathString.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            val path = os.Path(pathString)
            if os.isFile(path) then md.update(os.read.bytes(path))
        }
        md.digest().map(byte => "%02x".format(byte)).mkString

    def compileSourcesToTasty(
        sources: Seq[os.Path],
        classpath: Seq[os.Path],
        outputDir: os.Path,
        log: String => Unit
    ): Seq[os.Path] =
        if sources.isEmpty then Seq.empty
        else
            val classLoader = codegenClassLoader(toolClasspath = classpath, userClasspath = classpath)
            os.remove.all(outputDir)
            os.makeDir.all(outputDir)
            val fullClasspath = classpath.distinct.map(_.toString).mkString(File.pathSeparator)
            val args = (Seq(
                "-d",
                outputDir.toString,
                "-classpath",
                fullClasspath,
                "-nowarn"
            ) ++ sources.map(_.toString)).toArray

            try
                val mainCls  = classLoader.loadClass("dotty.tools.dotc.Main")
                val process  = mainCls.getMethods.find(m => m.getName == "process" && m.getParameterCount == 1).get
                val reporter = process.invoke(null, args.asInstanceOf[AnyRef])
                val hasErrors = reporter.getClass
                    .getMethod("hasErrors")
                    .invoke(reporter)
                    .asInstanceOf[java.lang.Boolean]
                    .booleanValue()
                if hasErrors then
                    log("[kymora-kyo-mill] ffiGenerate scratch TASTy compile reported errors; skipping codegen.")
                    Seq.empty
                else collectTastyFiles(outputDir)
            catch
                case throwable: Throwable =>
                    log(s"[kymora-kyo-mill] ffiGenerate scratch TASTy compile failed: ${throwable.getMessage}")
                    Seq.empty

    def generate(
        tastyFiles: Seq[os.Path],
        userClasspath: Seq[os.Path],
        toolClasspath: Seq[os.Path],
        outputDir: os.Path,
        platform: FfiTargetPlatform,
        libraryId: Option[String],
        libraries: Seq[FfiLibrary],
        strictBlocking: Boolean,
        strictCallbacks: Boolean,
        includeDirs: Seq[os.Path],
        warn: String => Unit
    ): Generated =
        if platform == FfiTargetPlatform.Wasm then throw platform.unsupported()
        if tastyFiles.isEmpty then Generated(Seq.empty, Seq.empty)
        else
            val classLoader  = codegenClassLoader(toolClasspath, userClasspath)
            val generatorCls = classLoader.loadClass("kyo.ffi.codegen.FfiGenerator$")
            val generator    = generatorCls.getField("MODULE$").get(null)

            val platformCls   = classLoader.loadClass("kyo.ffi.codegen.FfiGenerator$Platform")
            val platformValue = platformCls.getMethod("valueOf", classOf[String]).invoke(null, platform.codegenName)

            val configObjCls  = classLoader.loadClass("kyo.ffi.codegen.FfiGenerator$Config$")
            val configObj     = configObjCls.getField("MODULE$").get(null)
            val defaultConfig = configObjCls.getMethod("default").invoke(configObj)

            val configCls      = classLoader.loadClass("kyo.ffi.codegen.FfiGenerator$Config")
            val copyMethod     = configCls.getMethods.find(_.getName == "copy").get
            val extraLibraries = toLibraryConfigs(classLoader, libraries)

            val someCls = classLoader.loadClass("scala.Some")
            val noneObj = classLoader.loadClass("scala.None$").getField("MODULE$").get(null)
            val libOpt = libraryId match
                case Some(value) => someCls.getConstructor(classOf[Object]).newInstance(value).asInstanceOf[AnyRef]
                case None        => noneObj.asInstanceOf[AnyRef]

            val config = copyMethod.invoke(
                defaultConfig,
                libOpt,
                extraLibraries,
                java.lang.Boolean.valueOf(strictBlocking),
                java.lang.Boolean.valueOf(strictCallbacks),
                toScalaList(classLoader, includeDirs.map(_.toString))
            )

            val generateMethod = generatorCls.getMethods.find(_.getName == "generate").get
            val result = generateMethod.invoke(
                generator,
                toScalaList(classLoader, tastyFiles.map(_.toString)),
                toScalaList(classLoader, userClasspath.map(_.toString)),
                outputDir.toNIO,
                platformValue,
                config
            )

            val resultCls = classLoader.loadClass("kyo.ffi.codegen.FfiGenerator$Result")
            val files = scalaSeqToList(resultCls.getMethod("files").invoke(result).asInstanceOf[AnyRef]).map(path =>
                os.Path(path.asInstanceOf[java.nio.file.Path])
            )
            val warnings =
                scalaSeqToList(resultCls.getMethod("warnings").invoke(result).asInstanceOf[AnyRef]).map(_.toString)
            val traitsRaw = scalaSeqToList(resultCls.getMethod("traits").invoke(result).asInstanceOf[AnyRef])
            warnings.foreach(warn)
            val traits = traitsRaw.map { value =>
                val cls = value.getClass
                TraitInfo(
                    fqcn = cls.getMethod("fqcn").invoke(value).asInstanceOf[String],
                    simpleName = cls.getMethod("simpleName").invoke(value).asInstanceOf[String],
                    packageName = cls.getMethod("packageName").invoke(value).asInstanceOf[String],
                    library = cls.getMethod("library").invoke(value).asInstanceOf[String]
                )
            }
            Generated(files, traits)

    def collectTastyFiles(dir: os.Path): Seq[os.Path] =
        if !os.exists(dir) then Seq.empty
        else os.walk(dir).filter(path => path.last.endsWith(".tasty"))

    private def codegenClassLoader(toolClasspath: Seq[os.Path], userClasspath: Seq[os.Path]): ClassLoader =
        val urls = (toolClasspath ++ userClasspath).distinct.map(_.toIO.toURI.toURL).toArray
        URLClassLoader(urls, null)

    private def toLibraryConfigs(classLoader: ClassLoader, libraries: Seq[FfiLibrary]): AnyRef =
        val libraryConfigCls = classLoader.loadClass("kyo.ffi.codegen.FfiGenerator$LibraryConfig")
        val ctor             = libraryConfigCls.getConstructors.find(_.getParameterCount == 3).get
        val values = libraries.map { library =>
            ctor.newInstance(
                library.id,
                toScalaListAny(classLoader, library.cSources.map(_.toNIO.asInstanceOf[AnyRef])),
                toScalaList(classLoader, library.linkLibs)
            ).asInstanceOf[AnyRef]
        }
        toScalaListAny(classLoader, values)

    private def toScalaList(classLoader: ClassLoader, values: Seq[String]): AnyRef =
        toScalaListAny(classLoader, values.map(_.asInstanceOf[AnyRef]))

    private def toScalaListAny(classLoader: ClassLoader, values: Seq[AnyRef]): AnyRef =
        val nilObj = classLoader.loadClass("scala.collection.immutable.Nil$").getField("MODULE$").get(null)
        values.foldRight(nilObj.asInstanceOf[AnyRef]) { (head, tail) =>
            val consCls = classLoader.loadClass("scala.collection.immutable.$colon$colon")
            val ctor = consCls.getConstructor(classOf[Object], classLoader.loadClass("scala.collection.immutable.List"))
            ctor.newInstance(head, tail).asInstanceOf[AnyRef]
        }

    private def scalaSeqToList(seq: AnyRef): List[Any] =
        val iterator = seq.getClass.getMethod("iterator").invoke(seq)
        val hasNext  = iterator.getClass.getMethod("hasNext")
        val next     = iterator.getClass.getMethod("next")
        val out      = scala.collection.mutable.ListBuffer.empty[Any]
        while hasNext.invoke(iterator).asInstanceOf[java.lang.Boolean].booleanValue() do out += next.invoke(iterator)
        out.toList
