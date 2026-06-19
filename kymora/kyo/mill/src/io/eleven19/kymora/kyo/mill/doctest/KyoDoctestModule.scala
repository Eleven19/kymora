package io.eleven19.kymora.kyo.mill.doctest

import _root_.mill.*
import _root_.mill.scalalib.*
import io.eleven19.kymora.kyo.mill.KyoMillDefaults

import java.io.File
import java.nio.charset.StandardCharsets

/** Mill-native Kyo doctest integration.
  *
  * The integration follows Mill's normal module/task model: users mix this trait into a Scala module and run explicit
  * commands such as `doctest` or `doctestClean`. Validation commands never rewrite tracked files.
  *
  * {{{
  * import io.eleven19.kymora.kyo.mill.doctest.KyoDoctestModule
  *
  * object docs extends ScalaModule with KyoDoctestModule:
  *   def scalaVersion = "3.8.4"
  *   override def doctestSources = Task.Sources(moduleDir / "README.md")
  * }}}
  */
trait KyoDoctestModule extends ScalaModule:
    def kyoVersion: T[String] = Task(KyoMillDefaults.kyoVersion)

    /** Markdown or Scala documentation files scanned for doctest snippets. */
    def doctestSources: T[Seq[PathRef]] = Task.Sources(moduleDir / "README.md")

    /** Persistent cache used by `doctest`. */
    def doctestCacheDir: T[PathRef] = Task(PathRef(Task.dest / "cache"))

    /** Additional source text prepended to doctest snippets. */
    def doctestPredef: T[Seq[String]] = Task(Seq.empty)

    /** Maximum number of snippets compiled concurrently by kyo-doctest. */
    def doctestParallelism: T[Int] = Task(1)

    /** JVM options for the forked kyo-doctest CLI. */
    def doctestForkJavaOptions: T[Seq[String]] = Task(Seq.empty)

    /** Tool dependency resolved only when doctest commands run. */
    def doctestToolMvnDeps: T[Seq[Dep]] = Task {
        Seq(mvn"io.getkyo:kyo-doctest_3:${kyoVersion()}")
    }

    /** Validate configured documentation snippets, using the persistent cache. */
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
            freshDriver = false,
            writeCache = true,
            info = Task.log.info(_),
            warn = Task.log.warn(_)
        )
    }

    /** Validate snippets with a fresh driver and disposable cache. */
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
            freshDriver = true,
            writeCache = false,
            info = Task.log.info(_),
            warn = Task.log.warn(_)
        )
    }

    /** Remove the persistent doctest cache. */
    def doctestClean(): Command[Unit] = Task.Command {
        os.remove.all(doctestCacheDir().path)
    }

    /** Reserved for future kyo-doctest formatting support.
      *
      * The current Kyo doctest CLI validates snippets but does not expose a stable formatter entrypoint for non-sbt
      * integrations.
      */
    def doctestFormat(): Command[Unit] = Task.Command {
        throw new UnsupportedOperationException("kyo-doctest does not expose a Mill formatting entrypoint yet")
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
        freshDriver: Boolean,
        writeCache: Boolean,
        info: String => Unit,
        warn: String => Unit
    ): Unit =
        if sources.isEmpty then
            info("doctest: no sources to validate")
            return

        val effectiveCache =
            if writeCache then persistentCache
            else os.temp.dir(prefix = "kymora-kyo-doctest-")

        os.makeDir.all(effectiveCache)

        val classpath  = reconcileScala3Library(projectClasspath, toolClasspath)
        val configFile = dest / "doctest-config.json"
        val resultFile = dest / "doctest-result.json"
        val _          = os.remove(resultFile)

        val configJson = DoctestJson.encodeConfig(
            sources = sources,
            classpath = classpath.map(_.path),
            scalacOpts = scalacOpts,
            cacheDir = effectiveCache,
            parallel = parallel,
            predef = predef,
            freshDriver = freshDriver
        )
        os.write.over(configFile, configJson, createFolders = true)

        val javaBin =
            os.Path(System.getProperty("java.home")) / "bin" /
                (if System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows") then "java.exe"
                 else "java")

        val command = Seq(javaBin.toString) ++ forkJavaOptions ++ Seq(
            "-cp",
            classpath.map(ref => ref.path.toString).mkString(File.pathSeparator),
            "kyo.doctest.internal.cli.Main",
            configFile.toString,
            resultFile.toString
        )

        info(s"doctest: validating ${sources.map(_.last).mkString(", ")} (${classpath.size} classpath entries)")
        val result = os
            .proc(command)
            .call(cwd = os.pwd, check = false, stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)

        if os.exists(resultFile) && os.size(resultFile) > 0 then
            DoctestJson.decodeReport(os.read(resultFile)) match
                case Some(report) =>
                    info(
                        s"doctest: total=${report.totalBlocks} compiled=${report.compiled} cacheHits=${report.cacheHits} warnings=${report.warnings} failures=${report.failureCount}"
                    )
                    val summary =
                        s"total=${report.totalBlocks} compiled=${report.compiled} cacheHits=${report.cacheHits} warnings=${report.warnings} failures=${report.failureCount}"
                    os.write.over(effectiveCache / "last-summary.txt", summary, createFolders = true)
                case None =>
                    warn("doctest: result report could not be parsed")

        if !writeCache then os.remove.all(effectiveCache)

        if result.exitCode != 0 then
            throw new RuntimeException(
                s"doctest: validation failed (exit code ${result.exitCode}). See output above for details."
            )

    private def reconcileScala3Library(projectClasspath: Seq[PathRef], toolClasspath: Seq[PathRef]): Seq[PathRef] =
        val toolHasScala3Library = toolClasspath.exists(ref => ref.path.last.startsWith("scala3-library_3-"))
        val filteredProject =
            if toolHasScala3Library then
                projectClasspath.filterNot(ref => ref.path.last.startsWith("scala3-library_3-"))
            else projectClasspath
        (filteredProject ++ toolClasspath).distinctBy(_.path)

final private case class DoctestReport(
    totalBlocks: Int,
    cacheHits: Int,
    compiled: Int,
    warnings: Int,
    failureCount: Int
)

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

    def decodeReport(json: String): Option[DoctestReport] =
        try
            Some(
                DoctestReport(
                    totalBlocks = extractInt(json, "totalBlocks").getOrElse(0),
                    cacheHits = extractInt(json, "cacheHits").getOrElse(0),
                    compiled = extractInt(json, "compiled").getOrElse(0),
                    warnings = extractInt(json, "warnings").getOrElse(0),
                    failureCount = extractArrayLength(json, "failures")
                )
            )
        catch case _: Exception => None

    private def quoteJson(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private def extractInt(json: String, key: String): Option[Int] =
        val keyMarker = s""""$key""""
        val keyIdx    = json.indexOf(keyMarker)
        if keyIdx < 0 then None
        else
            val colonIdx = json.indexOf(':', keyIdx + keyMarker.length)
            if colonIdx < 0 then None
            else
                val rest = json.substring(colonIdx + 1).dropWhile(c => c == ' ' || c == '\n' || c == '\r' || c == '\t')
                val numStr = rest.takeWhile(_.isDigit)
                if numStr.isEmpty then None
                else
                    try Some(numStr.toInt)
                    catch case _: NumberFormatException => None

    private def extractArrayLength(json: String, key: String): Int =
        val keyMarker = s""""$key""""
        val keyIdx    = json.indexOf(keyMarker)
        if keyIdx < 0 then return 0
        val openBracket = json.indexOf('[', keyIdx + keyMarker.length)
        if openBracket < 0 then return 0

        var count       = 0
        var objectDepth = 0
        var inString    = false
        var i           = openBracket + 1
        var done        = false
        while i < json.length && !done do
            val c = json(i)
            if inString then
                if c == '\\' then i += 1
                else if c == '"' then inString = false
            else
                c match
                    case '"' =>
                        inString = true
                    case '{' =>
                        if objectDepth == 0 then count += 1
                        objectDepth += 1
                    case '}' =>
                        objectDepth -= 1
                    case ']' if objectDepth == 0 =>
                        done = true
                    case _ => ()
            i += 1
        count
