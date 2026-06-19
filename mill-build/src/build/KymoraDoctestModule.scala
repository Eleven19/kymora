package build

import mill.*
import mill.scalalib.*

import java.io.File

trait KymoraDoctestModule extends ScalaModule:
    def kyoVersion: T[String] = Task(KymoraVersions.Kyo)

    def doctestSources: T[Seq[PathRef]] = Task.Sources(moduleDir / "README.md")

    def doctestCacheDir: T[PathRef] = Task(PathRef(Task.dest / "cache"))

    def doctestPredef: T[Seq[String]] = Task(Seq.empty)

    def doctestParallelism: T[Int] = Task(1)

    def doctestForkJavaOptions: T[Seq[String]] = Task(Seq.empty)

    def doctestToolMvnDeps: T[Seq[Dep]] = Task {
        Seq(mvn"io.getkyo:kyo-doctest_3:${kyoVersion()}")
    }

    def doctest(): Command[Unit] = Task.Command {
        runDoctest(
            sources = doctestSources().map(_.path),
            persistentCache = doctestCacheDir().path,
            projectClasspath = transitiveCompileClasspath(),
            toolClasspath = defaultResolver().classpath(doctestToolMvnDeps()),
            scalacOpts = allScalacOptions(),
            parallel = doctestParallelism(),
            predef = doctestPredef(),
            forkJavaOptions = doctestForkJavaOptions(),
            dest = Task.dest,
            writeCache = true,
            freshDriver = false,
            info = Task.log.info(_)
        )
    }

    def doctestFresh(): Command[Unit] = Task.Command {
        runDoctest(
            sources = doctestSources().map(_.path),
            persistentCache = doctestCacheDir().path,
            projectClasspath = transitiveCompileClasspath(),
            toolClasspath = defaultResolver().classpath(doctestToolMvnDeps()),
            scalacOpts = allScalacOptions(),
            parallel = doctestParallelism(),
            predef = doctestPredef(),
            forkJavaOptions = doctestForkJavaOptions(),
            dest = Task.dest,
            writeCache = false,
            freshDriver = true,
            info = Task.log.info(_)
        )
    }

    def doctestClean(): Command[Unit] = Task.Command {
        os.remove.all(doctestCacheDir().path)
    }

    private def runDoctest(
        sources: Seq[os.Path],
        persistentCache: os.Path,
        projectClasspath: Seq[PathRef],
        toolClasspath: Seq[PathRef],
        scalacOpts: Seq[String],
        parallel: Int,
        predef: Seq[String],
        forkJavaOptions: Seq[String],
        dest: os.Path,
        writeCache: Boolean,
        freshDriver: Boolean,
        info: String => Unit
    ): Unit =
        if sources.isEmpty then
            info("doctest: no sources to validate")
            return

        val effectiveCache =
            if writeCache then persistentCache
            else os.temp.dir(prefix = "kymora-build-doctest-")
        os.makeDir.all(effectiveCache)

        val classpath  = reconcileScala3Library(projectClasspath, toolClasspath)
        val configFile = dest / "doctest-config.json"
        val resultFile = dest / "doctest-result.json"
        os.remove(resultFile)

        os.write.over(
            configFile,
            DoctestJson.encodeConfig(
                sources = sources,
                classpath = classpath.map(_.path),
                scalacOpts = scalacOpts,
                cacheDir = effectiveCache,
                parallel = parallel,
                predef = predef,
                freshDriver = freshDriver
            ),
            createFolders = true
        )

        val javaBin =
            os.Path(System.getProperty("java.home")) / "bin" /
                (if System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows") then "java.exe"
                 else "java")
        val command = Seq(javaBin.toString) ++ forkJavaOptions ++ Seq(
            "-cp",
            classpath.map(_.path.toString).mkString(File.pathSeparator),
            "kyo.doctest.internal.cli.Main",
            configFile.toString,
            resultFile.toString
        )

        info(s"doctest: validating ${sources.map(_.last).mkString(", ")}")
        val result = os.proc(command).call(cwd = os.pwd, check = false, stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)
        if !writeCache then os.remove.all(effectiveCache)
        if result.exitCode != 0 then
            throw RuntimeException(s"doctest: validation failed (exit code ${result.exitCode}). See output above for details.")

    private def reconcileScala3Library(projectClasspath: Seq[PathRef], toolClasspath: Seq[PathRef]): Seq[PathRef] =
        val toolHasScala3Library = toolClasspath.exists(ref => ref.path.last.startsWith("scala3-library_3-"))
        val filteredProject =
            if toolHasScala3Library then projectClasspath.filterNot(ref => ref.path.last.startsWith("scala3-library_3-"))
            else projectClasspath
        (filteredProject ++ toolClasspath).distinctBy(_.path)

private object DoctestJson:
    def encodeConfig(
        sources: Seq[os.Path],
        classpath: Seq[os.Path],
        scalacOpts: Seq[String],
        cacheDir: os.Path,
        parallel: Int,
        predef: Seq[String],
        freshDriver: Boolean
    ): String =
        val srcArr    = sources.map(path => quoteJson(path.toString))
        val cpArr     = classpath.map(path => quoteJson(path.toString))
        val optsArr   = scalacOpts.map(quoteJson)
        val predefArr = predef.map(quoteJson)
        s"""{
  "sources": [${srcArr.mkString(", ")}],
  "classpath": [${cpArr.mkString(", ")}],
  "scalaOpts": [${optsArr.mkString(", ")}],
  "cache": ${quoteJson(cacheDir.toString)},
  "parallel": $parallel,
  "predef": [${predefArr.mkString(", ")}],
  "freshDriver": $freshDriver
}"""

    private def quoteJson(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
