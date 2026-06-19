package io.eleven19.kymora.website

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.Comparator
import scala.jdk.CollectionConverters.*
import scala.meta.*
import scala.meta.tokens.Token as MetaToken
import scala.util.matching.Regex
import scala.sys.process.*

final class WebsiteException(message: String, cause: Throwable | Null = null) extends RuntimeException(message, cause)

final case class WebsiteVersion(tag: String, label: String, latest: Boolean) derives CanEqual

final case class WebsiteModule(
    slug: String,
    group: String,
    title: String,
    target: String,
    readme: String,
    summary: String,
    platforms: WebsiteModule.Platforms
) derives CanEqual

object WebsiteModule:
    final case class Platforms(jvm: Boolean, js: Boolean, native: Boolean, wasm: Boolean) derives CanEqual

final case class WebsiteContent(
    intro: String,
    groups: Vector[WebsiteContent.Group],
    version: WebsiteVersion
) derives CanEqual

object WebsiteContent:
    final case class Group(name: String, modules: Vector[WebsiteModule]) derives CanEqual

    def fromRepo(root: Path, version: WebsiteVersion): WebsiteContent =
        val readme = readRequired(root.resolve("README.md"))
        WebsiteContent(readme, parseGroups(root, readme), version)

    private def readRequired(path: Path): String =
        try Files.readString(path, StandardCharsets.UTF_8)
        catch
            case _: IOException =>
                throw new WebsiteException(s"missing README: $path")

    private def parseGroups(root: Path, readme: String): Vector[Group] =
        sectionBody(readme, "## Modules") match
            case None => Vector.empty
            case Some(body) =>
                splitGroups(body).map { case (name, lines) =>
                    val rows     = lines.filter(_.trim.startsWith("|"))
                    val dataRows = rows.filterNot(isSeparatorRow).drop(1)
                    val modules  = dataRows.flatMap(row => parseModule(root, name, row))
                    val hasTable = rows.size >= 2 && rows.exists(isSeparatorRow)
                    if !hasTable then throw new WebsiteException(s"malformed module table for group: $name")
                    else Group(name, modules)
                }

    private def sectionBody(text: String, marker: String): Option[String] =
        val lines = text.linesIterator.toVector
        val start = lines.indexWhere(_.trim == marker)
        if start < 0 then None
        else
            val rest = lines.drop(start + 1)
            val end  = rest.indexWhere(line => line.startsWith("## ") && line.trim != marker)
            Some((if end >= 0 then rest.take(end) else rest).mkString("\n"))

    private def splitGroups(body: String): Vector[(String, Vector[String])] =
        val (closed, openName, openLines) =
            body.linesIterator.foldLeft(
                (Vector.empty[(String, Vector[String])], Option.empty[String], Vector.empty[String])
            ) {
                case ((acc, current, lines), line) if line.startsWith("### ") =>
                    val closedAcc = current.fold(acc)(name => acc :+ (name -> lines))
                    (closedAcc, Some(line.stripPrefix("### ").trim), Vector.empty[String])
                case ((acc, Some(name), lines), line) =>
                    (acc, Some(name), lines :+ line)
                case (state, _) =>
                    state
            }
        openName.fold(closed)(name => closed :+ (name -> openLines))

    private def parseModule(root: Path, group: String, row: String): Option[WebsiteModule] =
        val cells = pipeCells(row)
        if cells.size < 5 then throw new WebsiteException(s"malformed module table row: $row")
        val link = parseLink(cells.head).getOrElse(throw new WebsiteException(s"malformed module link: ${cells.head}"))
        if !link.target.endsWith("README.md") then None
        else
            val readmePath = root.resolve(link.target).normalize()
            Some(
                WebsiteModule(
                    slug = slug(link.label),
                    group = group,
                    title = link.label,
                    target = link.target,
                    readme = readRequired(readmePath),
                    summary = cells.lift(5).map(stripBackticks).getOrElse(""),
                    platforms = WebsiteModule.Platforms(
                        jvm = supported(cells(1)),
                        js = supported(cells(2)),
                        native = supported(cells(3)),
                        wasm = cells.size >= 6 && supported(cells(4))
                    )
                )
            )

    final private case class Link(label: String, target: String)

    private def parseLink(cell: String): Option[Link] =
        val openBracket  = cell.indexOf('[')
        val closeBracket = cell.indexOf(']', openBracket + 1)
        val openParen    = cell.indexOf('(', closeBracket + 1)
        val closeParen   = cell.indexOf(')', openParen + 1)
        if openBracket < 0 || closeBracket < 0 || openParen < 0 || closeParen < 0 then None
        else
            Some(
                Link(
                    stripBackticks(cell.substring(openBracket + 1, closeBracket)),
                    cell.substring(openParen + 1, closeParen)
                )
            )

    private def stripBackticks(text: String): String =
        text.stripPrefix("`").stripSuffix("`")

    private def slug(label: String): String =
        label.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")

    private def supported(cell: String): Boolean =
        val normalized = cell.trim.toLowerCase(java.util.Locale.ROOT)
        normalized == "✅" || normalized == "yes" || normalized == "true" || normalized == "y" || normalized == "✓"

    private def isSeparatorRow(line: String): Boolean =
        val cells = pipeCells(line)
        cells.nonEmpty && cells.forall(cell => cell.nonEmpty && cell.forall(ch => ch == '-' || ch == ':'))

    private def pipeCells(line: String): Vector[String] =
        val trimmed = line.trim
        if !trimmed.startsWith("|") then Vector.empty
        else trimmed.stripPrefix("|").stripSuffix("|").split("\\|", -1).toVector.map(_.trim)

object WebsiteVersion:

    def current(repoRoot: Path): WebsiteVersion =
        latestTag(repoRoot) match
            case Some(tag) => WebsiteVersion(tag, tag.stripPrefix("v"), latest = true)
            case None      => WebsiteVersion("current", "current", latest = true)

    private def latestTag(repoRoot: Path): Option[String] =
        try
            val logger = ProcessLogger(_ => (), _ => ())
            val out = Seq(
                "git",
                "-C",
                repoRoot.toString,
                "for-each-ref",
                "--sort=-creatordate",
                "--format=%(refname:short)",
                "refs/tags/v[0-9]*"
            ).!!(logger)
            out.linesIterator.find(parse(_).isDefined)
        catch case _: Throwable => None

    def parse(tag: String): Option[(Int, Int, Int, String)] =
        val Pattern = """^v(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-(.+))?$""".r
        tag match
            case Pattern(major, minor, patch, pre) =>
                Some(
                    (
                        major.toInt,
                        Option(minor).fold(0)(_.toInt),
                        Option(patch).fold(0)(_.toInt),
                        Option(pre).getOrElse("")
                    )
                )
            case _ => None

object WebsiteGenerator:
    final case class Config(repoRoot: Path, bundleDir: Path, basePath: String) derives CanEqual

    def emit(contents: Seq[WebsiteContent], outDir: Path, config: Config): Unit =
        if !Files.exists(config.bundleDir.resolve("main.js")) then
            throw new WebsiteException(s"missing bundle: ${config.bundleDir.resolve("main.js")}")
        deleteDirectory(outDir)
        Files.createDirectories(outDir)
        copy(config.bundleDir.resolve("main.js"), outDir.resolve("main.js"))
        if Files.exists(config.bundleDir.resolve("main.js.map")) then
            copy(config.bundleDir.resolve("main.js.map"), outDir.resolve("main.js.map"))
        copyRequiredAsset(config.repoRoot, outDir, "kymora.svg")
        copyRequiredAsset(config.repoRoot, outDir, "kymora-icon.svg")
        write(outDir.resolve(".nojekyll"), "")
        val ordered  = contents.toVector
        val versions = ordered.map(_.version)
        write(outDir.resolve("index.html"), landingPage(ordered.lastOption, versions, config.basePath))
        ordered.foreach(content => emitVersion(outDir, content, content.version.tag, versions, config.basePath))
        ordered
            .find(_.version.latest)
            .foreach(content => emitVersion(outDir, content, "latest", versions, config.basePath))
        write(outDir.resolve("versions.json"), versionsJson(ordered))
        write(outDir.resolve("sitemap.xml"), sitemap(ordered, config.basePath))
        write(
            outDir.resolve("robots.txt"),
            s"User-agent: *\nAllow: /\nSitemap: ${joinPath(config.basePath, "sitemap.xml")}\n"
        )

    private def emitVersion(
        outDir: Path,
        content: WebsiteContent,
        prefix: String,
        versions: Seq[WebsiteVersion],
        basePath: String
    ): Unit =
        val versionDir = outDir.resolve(prefix)
        Files.createDirectories(versionDir)
        val modules = content.groups.flatMap(_.modules)
        write(
            versionDir.resolve("index.html"),
            docsPage(
                content.version.label,
                "Overview",
                renderMarkdown(content.intro, basePath),
                basePath,
                prefix,
                versions,
                content
            )
        )
        write(versionDir.resolve("content.md"), content.intro)
        write(versionDir.resolve("content.html"), renderMarkdown(content.intro, basePath))
        write(versionDir.resolve("manifest.json"), manifestJson(prefix, content))
        write(versionDir.resolve("search-index.json"), searchJson(prefix, content))
        modules.foreach { module =>
            val dir = versionDir.resolve(module.slug)
            Files.createDirectories(dir)
            write(
                dir.resolve("index.html"),
                docsPage(
                    content.version.label,
                    module.title,
                    renderMarkdown(module.readme, basePath),
                    basePath,
                    prefix,
                    versions,
                    content
                )
            )
            write(dir.resolve("content.md"), module.readme)
            write(dir.resolve("content.html"), renderMarkdown(module.readme, basePath))
        }

    private def landingPage(latest: Option[WebsiteContent], versions: Seq[WebsiteVersion], basePath: String): String =
        val docsHref = joinPath(basePath, "latest/")
        page(
            title = "Kymora",
            basePath = basePath,
            version = latest.fold("current")(_.version.label),
            searchPrefix = "latest",
            currentPrefix = "latest",
            versions = versions,
            body = s"""<main class="landing">
                   |  <section class="hero">
                   |    <div class="hero-copy">
                   |      <p class="eyebrow">Built for Kyo</p>
                   |      <h1>Kymora</h1>
                   |      <p class="lead">Cross-platform Kyo extensions for virtual filesystems, workflow execution, published test helpers, and Mill integrations.</p>
                   |      <div class="actions"><a class="button primary" href="$docsHref">Read the docs</a><a class="button" href="https://github.com/Eleven19/kymora">GitHub</a></div>
                   |    </div>
                   |    ${renderCodeCard(
                         "scala",
                         "val runtime = Workflow.Runtime(vfs)\nWorkflow.handle(runtime)(compile())"
                     )}
                   |  </section>
                   |  <section class="module-grid">
                   |    ${latest.fold("")(moduleCards)}
                   |  </section>
                   |</main>""".stripMargin
        )

    private def moduleCards(content: WebsiteContent): String =
        content.groups
            .flatMap(_.modules)
            .map(module =>
                s"""<a class="module-card" href="latest/${module.slug}/">
                   |  <span class="module-group">${escape(module.group)}</span>
                   |  <strong>${escape(module.title)}</strong>
                   |  <p class="module-summary">${escape(module.summary)}</p>
                   |  <small class="module-platforms">${platforms(module.platforms)}</small>
                   |</a>""".stripMargin
            )
            .mkString("\n")

    private def docsPage(
        version: String,
        title: String,
        article: String,
        basePath: String,
        prefix: String,
        versions: Seq[WebsiteVersion],
        content: WebsiteContent
    ): String =
        val nav = content.groups
            .map(group =>
                s"""<section class="nav-group"><h2>${escape(group.name)}</h2>${group.modules
                        .map(m =>
                            s"""<a href="${joinPath(basePath, prefix + "/" + m.slug + "/")}">${escape(m.title)}</a>"""
                        )
                        .mkString}</section>"""
            )
            .mkString
        page(
            s"$title | Kymora",
            basePath,
            version,
            prefix,
            prefix,
            versions,
            s"""<main class="docs">
               |  <aside class="sidebar"><a class="brand" href="${joinPath(
                  basePath,
                  ""
              )}">Kymora</a><a href="${joinPath(basePath, prefix + "/")}">Overview $version</a>$nav</aside>
               |  <article class="article">$article</article>
               |</main>
               |<script id="docs-island" type="application/json">${escapeJson(
                  manifestJson(prefix, content)
              )}</script>""".stripMargin
        )

    private def page(
        title: String,
        basePath: String,
        version: String,
        searchPrefix: String,
        currentPrefix: String,
        versions: Seq[WebsiteVersion],
        body: String
    ): String =
        s"""<!doctype html>
           |<html lang="en">
           |<head>
           |<meta charset="utf-8">
           |<meta name="viewport" content="width=device-width, initial-scale=1">
           |<title>${escape(title)}</title>
           |<link rel="icon" type="image/svg+xml" href="${joinPath(basePath, "kymora-icon.svg")}">
           |<style>${styles}</style>
           |<script type="module" src="${joinPath(basePath, "main.js")}"></script>
           |</head>
           |<body>${siteHeader(basePath, version, searchPrefix, currentPrefix, versions)}$body</body>
           |</html>""".stripMargin

    private def siteHeader(
        basePath: String,
        version: String,
        searchPrefix: String,
        currentPrefix: String,
        versions: Seq[WebsiteVersion]
    ): String =
        s"""<header class="site-header" data-section="header">
           |  <div class="site-header-inner">
           |    <a class="brand" data-role="logo" href="${joinPath(
              basePath,
              ""
          )}"><img class="mark" src="${joinPath(basePath, "kymora-icon.svg")}" alt="Kymora"><span>Kymora</span></a>
           |    <nav class="links"><a class="nav-link" href="${joinPath(
              basePath,
              "latest/"
          )}">$docsIcon<span>Docs</span></a><a class="nav-link" href="https://javadoc.io/doc/io.eleven19.kymora/kymora-vfs_3">$apiIcon<span>API</span></a><a class="nav-link soc" href="https://github.com/Eleven19/kymora">$githubIcon<span>GitHub</span></a></nav>
           |    <div class="right"><div class="search-wrap" data-search-index="${joinPath(
              basePath,
              s"$searchPrefix/search-index.json"
          )}"><input class="search-input" type="search" placeholder="Search docs" autocomplete="off" aria-label="Search docs"><div class="search-results" hidden></div></div><button class="theme-toggle" type="button" aria-label="Toggle dark mode"><span class="moon" aria-hidden="true">${moonIcon}</span><span class="sun" aria-hidden="true">${sunIcon}</span></button>${versionSelect(
              basePath,
              currentPrefix,
              versions
          )}<a class="btn btn-primary" href="${joinPath(basePath, "latest/")}">Get started</a></div>
           |  </div>
           |</header>""".stripMargin

    private def versionSelect(basePath: String, currentPrefix: String, versions: Seq[WebsiteVersion]): String =
        val latest = versions
            .find(_.latest)
            .map(_ => versionOption(basePath, "latest", "latest", currentPrefix == "latest"))
            .toSeq
        val tagged = versions.map(v => versionOption(basePath, v.tag, v.label, currentPrefix == v.tag))
        (latest ++ tagged).mkString(
            """<select class="version-select" aria-label="Select documentation version">""",
            "",
            "</select>"
        )

    private def versionOption(basePath: String, prefix: String, label: String, selected: Boolean): String =
        val selectedAttr = if selected then " selected" else ""
        s"""<option value="${joinPath(basePath, prefix + "/")}"$selectedAttr>${escape(label)}</option>"""

    private def renderMarkdown(markdown: String, basePath: String): String =
        def isBlockStart(line: String): Boolean =
            isFence(line) ||
                line.startsWith("# ") ||
                line.startsWith("## ") ||
                line.startsWith("### ") ||
                line.trim.startsWith("|") ||
                line.trim.startsWith("- ")

        val out   = StringBuilder()
        val lines = markdown.linesIterator.toVector
        var index = 0
        while index < lines.size do
            val line = lines(index)
            if isFence(line) then
                val indent    = fenceIndent(line)
                val info      = fenceInfo(line)
                val codeLines = Vector.newBuilder[String]
                index += 1
                while index < lines.size && !isFence(lines(index)) do
                    codeLines += stripFenceIndent(lines(index), indent)
                    index += 1
                if index < lines.size then index += 1
                out.append(renderCopyableCodeBlock(info, codeLines.result().mkString("\n")))
            else if line.startsWith("# ") then
                out.append(s"<h1>${escape(line.drop(2).trim)}</h1>")
                index += 1
            else if line.startsWith("## ") then
                out.append(s"<h2 id=\"${anchor(line.drop(3))}\">${escape(line.drop(3).trim)}</h2>")
                index += 1
            else if line.startsWith("### ") then
                out.append(s"<h3 id=\"${anchor(line.drop(4))}\">${escape(line.drop(4).trim)}</h3>")
                index += 1
            else if line.trim.startsWith("|") then
                val tableLines = lines.drop(index).takeWhile(_.trim.startsWith("|"))
                out.append(renderTable(tableLines))
                index += tableLines.size
            else if line.trim.startsWith("- ") then
                val (html, consumed) = renderList(lines.drop(index))
                out.append(html)
                index += consumed
            else if line.trim.isEmpty then
                out.append('\n')
                index += 1
            else
                val paragraph = lines
                    .drop(index)
                    .takeWhile(next => next.trim.nonEmpty && !isBlockStart(next))
                out.append(s"<p>${renderInline(paragraph.map(_.trim).mkString(" "), basePath)}</p>")
                index += paragraph.size
        out.toString

    private def isFence(line: String): Boolean =
        val indent = fenceIndent(line)
        indent >= 0 && line.drop(indent).startsWith("```")

    private def fenceIndent(line: String): Int =
        val indent = line.takeWhile(_ == ' ').length
        if indent <= 3 then indent else -1

    private def stripFenceIndent(line: String, indent: Int): String =
        line.drop(math.min(indent, line.takeWhile(_ == ' ').length))

    private def fenceInfo(line: String): String =
        line.trim.dropWhile(_ == '`').trim.takeWhile(ch => !ch.isWhitespace).toLowerCase(java.util.Locale.ROOT)

    private def renderCodeBlock(info: String, body: String): String =
        val lang = if info.isEmpty then "text" else info
        s"""<pre class="code" data-lang="${escapeAttribute(lang)}"><code>${highlight(lang, body)}</code></pre>"""

    private def renderCopyableCodeBlock(info: String, body: String): String =
        s"""<div class="code-block"><button class="code-copy" type="button" aria-label="Copy code to clipboard">$copyIcon<span class="code-copy-idle">Copy</span><span class="code-copy-done">Copied</span></button>${renderCodeBlock(
                info,
                body
            )}</div>"""

    private def renderCodeCard(info: String, body: String): String =
        s"""<div class="code-card" data-section="signature"><div class="code-bar"><div class="code-dots"><span></span><span></span><span></span></div><span class="code-lang">${escape(
                if info.isEmpty then "TEXT" else info.toUpperCase(java.util.Locale.ROOT)
            )}</span></div>${renderCodeBlock(info, body)}</div>"""

    private def highlight(lang: String, body: String): String =
        lang match
            case "scala" | "sbt"                        => highlightScala(body)
            case "bash" | "sh" | "shell" | "zsh" | "nu" => highlightShell(body, prompts = false)
            case "console" | "terminal"                 => highlightShell(body, prompts = true)
            case _                                      => escape(body)

    private enum TokenKind(val cssClass: String) derives CanEqual:
        case Keyword       extends TokenKind("tok-keyword")
        case Str           extends TokenKind("tok-string")
        case Comment       extends TokenKind("tok-comment")
        case Number        extends TokenKind("tok-number")
        case Type          extends TokenKind("tok-type")
        case Interpolation extends TokenKind("tok-interpolation")
        case Annotation    extends TokenKind("tok-annotation")
        case Operator      extends TokenKind("tok-operator")

    private def highlightScala(body: String): String =
        dialects.Scala3(body).tokenize match
            case success: Tokenized.Success =>
                val out  = StringBuilder()
                var prev = Option.empty[MetaToken]
                success.tokens.foreach { tok =>
                    appendToken(out, tok.text, classify(tok, prev))
                    if !isTrivia(tok) then prev = Some(tok)
                }
                out.toString
            case _ => escape(body)

    private def appendToken(out: StringBuilder, text: String, kind: Option[TokenKind]): Unit =
        kind match
            case Some(k) =>
                out.append(s"""<span class="${k.cssClass}">${escape(text)}</span>""")
                ()
            case None =>
                out.append(escape(text))
                ()

    private def isTrivia(tok: MetaToken): Boolean = tok match
        case _: MetaToken.HSpace | _: MetaToken.Space | _: MetaToken.Tab | _: MetaToken.CR | _: MetaToken.LF |
            _: MetaToken.FF | _: MetaToken.EOL | _: MetaToken.BOF | _: MetaToken.EOF =>
            true
        case _ => false

    private def classify(tok: MetaToken, prev: Option[MetaToken]): Option[TokenKind] = tok match
        case _: MetaToken.Comment => Some(TokenKind.Comment)
        case _: MetaToken.Constant.Int | _: MetaToken.Constant.Long | _: MetaToken.Constant.Float |
            _: MetaToken.Constant.Double =>
            Some(TokenKind.Number)
        case _: MetaToken.Constant.String | _: MetaToken.Constant.Char | _: MetaToken.Constant.Symbol =>
            Some(TokenKind.Str)
        case _: MetaToken.Interpolation.Id => Some(TokenKind.Interpolation)
        case _: MetaToken.Interpolation.Start | _: MetaToken.Interpolation.Part | _: MetaToken.Interpolation.End =>
            Some(TokenKind.Str)
        case _: MetaToken.Interpolation.SpliceStart | _: MetaToken.Interpolation.SpliceEnd =>
            Some(TokenKind.Operator)
        case _: MetaToken.At => Some(TokenKind.Annotation)
        case _: MetaToken.KwTrue | _: MetaToken.KwFalse | _: MetaToken.KwNull =>
            Some(TokenKind.Keyword)
        case _: MetaToken.Keyword => Some(TokenKind.Keyword)
        case _: MetaToken.Colon | _: MetaToken.Equals | _: MetaToken.RightArrow | _: MetaToken.FunctionArrow |
            _: MetaToken.Underscore | _: MetaToken.Hash | _: MetaToken.Subtype | _: MetaToken.Supertype =>
            Some(TokenKind.Operator)
        case id: MetaToken.Ident if isOperatorIdent(id.text) => Some(TokenKind.Operator)
        case _: MetaToken.Ident if prev.exists(_.isInstanceOf[MetaToken.At]) =>
            Some(TokenKind.Annotation)
        case id: MetaToken.Ident if isTypeIdent(id.text, prev) => Some(TokenKind.Type)
        case _                                                 => None

    private def isOperatorIdent(text: String): Boolean =
        text.nonEmpty && !(Character.isLetter(text.head) || text.head == '_' || text.head == '`')

    private def isTypeIdent(text: String, prev: Option[MetaToken]): Boolean =
        if text.nonEmpty && Character.isUpperCase(text.head) then true
        else
            prev.exists {
                case _: MetaToken.Colon | _: MetaToken.KwExtends | _: MetaToken.Subtype | _: MetaToken.Supertype |
                    _: MetaToken.KwNew | _: MetaToken.KwWith | _: MetaToken.LeftBracket | _: MetaToken.KwType =>
                    true
                case _ => false
            }

    private def highlightShell(body: String, prompts: Boolean): String =
        body.linesIterator.map(line => highlightShellLine(line, prompts)).mkString("\n")

    private def highlightShellLine(line: String, prompts: Boolean): String =
        val out   = StringBuilder()
        var index = if prompts then appendConsolePrompt(line, out) else 0
        while index < line.length do
            val ch = line.charAt(index)
            if ch.isWhitespace then
                out.append(ch)
                index += 1
            else if ch == '#' then
                appendShellToken(out, line.substring(index), TokenKind.Comment)
                index = line.length
            else if ch == '\'' || ch == '"' then
                val end = scanShellString(line, index, ch)
                appendShellToken(out, line.substring(index, end), TokenKind.Str)
                index = end
            else if ch == '$' then
                val end = scanShellVariable(line, index)
                appendShellToken(out, line.substring(index, end), TokenKind.Interpolation)
                index = end
            else
                val end   = scanShellWord(line, index)
                val token = line.substring(index, end)
                if isShellAssignment(token) then appendShellAssignment(out, token)
                else
                    classifyShellToken(token) match
                        case Some(kind) => appendShellToken(out, token, kind)
                        case None       => out.append(escape(token))
                index = end
        out.toString

    private def appendConsolePrompt(line: String, out: StringBuilder): Int =
        val leading = line.takeWhile(_.isWhitespace)
        out.append(escape(leading))
        val index = leading.length
        if index < line.length && (line.charAt(index) == '$' || line.charAt(index) == '#') then
            appendShellToken(out, line.charAt(index).toString, TokenKind.Operator)
            index + 1
        else index

    private def scanShellString(line: String, start: Int, quote: Char): Int =
        var index   = start + 1
        var escaped = false
        while index < line.length do
            val ch = line.charAt(index)
            if escaped then escaped = false
            else if quote == '"' && ch == '\\' then escaped = true
            else if ch == quote then return index + 1
            index += 1
        line.length

    private def scanShellVariable(line: String, start: Int): Int =
        if start + 1 < line.length && line.charAt(start + 1) == '{' then
            val close = line.indexOf('}', start + 2)
            if close >= 0 then close + 1 else start + 1
        else
            var index = start + 1
            while index < line.length && isShellNameChar(line.charAt(index)) do index += 1
            if index == start + 1 then start + 1 else index

    private def scanShellWord(line: String, start: Int): Int =
        var index = start
        while index < line.length && !line.charAt(index).isWhitespace && line.charAt(index) != '#' do index += 1
        index

    private def classifyShellToken(token: String): Option[TokenKind] =
        if ShellKeywords.contains(token) || ShellCommands.contains(token) then Some(TokenKind.Keyword)
        else if token.startsWith("--") || token.matches("-[A-Za-z0-9][A-Za-z0-9-]*") then Some(TokenKind.Annotation)
        else None

    private def isShellAssignment(token: String): Boolean =
        token.indexOf('=') match
            case -1 => false
            case index =>
                val name = token.take(index)
                name.nonEmpty && (name.head.isLetter || name.head == '_') && name.forall(isShellNameChar)

    private def appendShellAssignment(out: StringBuilder, token: String): Unit =
        val equals = token.indexOf('=')
        appendShellToken(out, token.take(equals), TokenKind.Interpolation)
        appendShellToken(out, "=", TokenKind.Operator)
        val value = token.drop(equals + 1)
        if value.startsWith("\"") || value.startsWith("'") then appendShellToken(out, value, TokenKind.Str)
        else out.append(escape(value))

    private def appendShellToken(out: StringBuilder, text: String, kind: TokenKind): Unit =
        out.append(s"""<span class="${kind.cssClass}">${escape(text)}</span>""")
        ()

    private def isShellNameChar(ch: Char): Boolean =
        ch.isLetterOrDigit || ch == '_'

    private def renderInline(line: String, basePath: String = ""): String =
        val codeRendered = InlineCode.replaceAllIn(
            escape(line),
            m => Regex.quoteReplacement(s"<code>${m.group(1)}</code>")
        )
        val imageRendered = ImageSyntax.replaceAllIn(
            codeRendered,
            m =>
                Regex.quoteReplacement(
                    s"""<img src="${imageSrc(m.group(2), basePath)}" alt="${escapeAttribute(m.group(1))}">"""
                )
        )
        val linksRendered = LinkSyntax.replaceAllIn(
            imageRendered,
            m => Regex.quoteReplacement(s"""<a href="${escapeAttribute(m.group(2))}">${m.group(1)}</a>""")
        )
        BoldSyntax.replaceAllIn(
            linksRendered,
            m => Regex.quoteReplacement(s"<strong>${m.group(1)}</strong>")
        )

    private def renderTable(lines: Seq[String]): String =
        val rows = lines.map(tableCells).filter(_.nonEmpty)
        if rows.size < 2 then rows.map(row => s"<p>${row.map(renderInline(_)).mkString(" | ")}</p>").mkString
        else
            val header = rows.head
            val body   = rows.drop(2)
            val headHtml =
                header.map(cell => s"<th>${renderInline(cell)}</th>").mkString("<thead><tr>", "", "</tr></thead>")
            val bodyHtml = body
                .map(row => row.map(cell => s"<td>${renderInline(cell)}</td>").mkString("<tr>", "", "</tr>"))
                .mkString("<tbody>", "", "</tbody>")
            s"<table>$headHtml$bodyHtml</table>"

    private def renderList(lines: Vector[String]): (String, Int) =
        val items    = Vector.newBuilder[String]
        var consumed = 0
        while consumed < lines.size && lines(consumed).trim.startsWith("- ") do
            val text = StringBuilder(lines(consumed).trim.drop(2).trim)
            consumed += 1
            while consumed < lines.size && lines(consumed).startsWith("  ") && !lines(consumed).trim.startsWith("- ") do
                if lines(consumed).trim.nonEmpty then
                    val _ = text.append(' ').append(lines(consumed).trim)
                consumed += 1
            items += s"<li>${renderInline(text.toString)}</li>"
        (items.result().mkString("<ul>", "", "</ul>"), consumed)

    private def tableCells(line: String): Vector[String] =
        val trimmed = line.trim
        if !trimmed.startsWith("|") then Vector.empty
        else trimmed.stripPrefix("|").stripSuffix("|").split("\\|", -1).toVector.map(_.trim)

    private def manifestJson(prefix: String, content: WebsiteContent): String =
        val groups = content.groups
            .map(group =>
                val modules = group.modules
                    .map(module =>
                        s"""{"slug":${json(module.slug)},"title":${json(module.title)},"href":${json(
                                s"/$prefix/${module.slug}/"
                            )}}"""
                    )
                    .mkString("[", ",", "]")
                s"""{"name":${json(group.name)},"modules":$modules}"""
            )
            .mkString("[", ",", "]")
        s"""{"version":${json(content.version.label)},"prefix":${json(prefix)},"groups":$groups}"""

    private def searchJson(prefix: String, content: WebsiteContent): String =
        val entries = content.groups
            .flatMap(_.modules)
            .map(module =>
                s"""{"title":${json(module.title)},"href":${json(s"/$prefix/${module.slug}/")},"text":${json(
                        stripMarkdown(module.readme)
                    )}}"""
            )
            .mkString("[", ",", "]")
        s"""{"entries":$entries}"""

    private def versionsJson(contents: Seq[WebsiteContent]): String =
        contents
            .map(c =>
                s"""{"tag":${json(c.version.tag)},"label":${json(c.version.label)},"latest":${c.version.latest}}"""
            )
            .mkString("[", ",", "]")

    private def sitemap(contents: Seq[WebsiteContent], basePath: String): String =
        val latest = contents.find(_.version.latest).toSeq
        val urls = latest.flatMap { content =>
            joinPath(basePath, "latest/") +: content.groups
                .flatMap(_.modules)
                .map(m => joinPath(basePath, s"latest/${m.slug}/"))
        }
        val today = LocalDate.now()
        urls.map(url => s"<url><loc>$url</loc><lastmod>$today</lastmod></url>")
            .mkString("""<?xml version="1.0" encoding="UTF-8"?><urlset>""", "", "</urlset>")

    private def platforms(p: WebsiteModule.Platforms): String =
        Vector("JVM" -> p.jvm, "JS" -> p.js, "Native" -> p.native, "WASM" -> p.wasm)
            .collect { case (name, true) => name }
            .mkString(" · ")

    private def stripMarkdown(text: String): String =
        text.linesIterator.filterNot(_.startsWith("#")).mkString(" ")

    private def anchor(text: String): String =
        text.trim.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")

    private def joinPath(basePath: String, path: String): String =
        val base = if basePath.endsWith("/") then basePath.dropRight(1) else basePath
        val tail = path.stripPrefix("/")
        if tail.isEmpty then if base.isEmpty then "/" else base + "/"
        else if base.isEmpty then "/" + tail
        else base + "/" + tail

    private def copy(from: Path, to: Path): Unit =
        Files.createDirectories(to.getParent)
        val _ = Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)

    private def copyRequiredAsset(repoRoot: Path, outDir: Path, name: String): Unit =
        val source = repoRoot.resolve(name)
        if !Files.exists(source) then throw new WebsiteException(s"missing website asset: $source")
        else copy(source, outDir.resolve(name))

    private def imageSrc(src: String, basePath: String): String =
        val trimmed = src.trim
        val external = trimmed.startsWith("http://") ||
            trimmed.startsWith("https://") ||
            trimmed.startsWith("/") ||
            trimmed.startsWith("#") ||
            trimmed.startsWith("data:")
        escapeAttribute(if external then trimmed else joinPath(basePath, trimmed))

    private def write(path: Path, text: String): Unit =
        Files.createDirectories(path.getParent)
        val _ = Files.writeString(path, text, StandardCharsets.UTF_8)

    private def deleteDirectory(path: Path): Unit =
        if Files.exists(path) then
            Files
                .walk(path)
                .sorted(Comparator.reverseOrder())
                .iterator()
                .asScala
                .foreach(p => Files.deleteIfExists(p))

    private def json(text: String): String =
        "\"" + escapeJson(text) + "\""

    private def escapeJson(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private def escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private def escapeAttribute(text: String): String =
        escape(text).replace("'", "&#39;")

    private val InlineCode  = "`([^`]+)`".r
    private val ImageSyntax = "!\\[([^\\]]*)\\]\\(([^\\)]+)\\)".r
    private val LinkSyntax  = "\\[([^\\]]+)\\]\\(([^\\)]+)\\)".r
    private val BoldSyntax  = "\\*\\*([^*]+)\\*\\*".r

    private val ShellKeywords =
        Set("case", "do", "done", "elif", "else", "esac", "fi", "for", "function", "if", "in", "then", "while")

    private val ShellCommands =
        Set(
            "cat",
            "cd",
            "chmod",
            "cp",
            "brew",
            "cargo",
            "curl",
            "echo",
            "env",
            "exit",
            "export",
            "find",
            "gh",
            "git",
            "grep",
            "java",
            "jj",
            "ls",
            "mkdir",
            "mv",
            "nu",
            "printf",
            "rg",
            "rm",
            "sed",
            "tar",
            "test",
            "unzip",
            "zip"
        )

    private val moonIcon =
        """<svg viewBox="0 0 24 24" focusable="false"><path d="M21 12.8A8.5 8.5 0 1 1 11.2 3 6.5 6.5 0 0 0 21 12.8Z"/></svg>"""

    private val sunIcon =
        """<svg viewBox="0 0 24 24" focusable="false"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M6.3 17.7l-1.4 1.4M19.1 4.9l-1.4 1.4"/></svg>"""

    private val copyIcon =
        """<svg class="code-copy-glyph" viewBox="0 0 24 24" aria-hidden="true"><rect x="9" y="9" width="10" height="10" rx="2"/><path d="M5 15V7a2 2 0 0 1 2-2h8"/></svg>"""

    private val docsIcon =
        """<svg class="nav-ic" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3h9l3 3v15H6z"/><path d="M14 3v4h4M9 11h6M9 15h6"/></svg>"""

    private val apiIcon =
        """<svg class="nav-ic" viewBox="0 0 24 24" aria-hidden="true"><path d="M8 8 4 12l4 4M16 8l4 4-4 4M14 4l-4 16"/></svg>"""

    private val githubIcon =
        """<svg class="brand-ic" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"/></svg>"""

    private val styles =
        """
        |:root{color-scheme:light dark;--bg:#FAF8F4;--surface:#FFFFFF;--ink:#16150F;--dim:#56534A;--faint:#8C887C;--line:#E8E3D9;--line-soft:#F0ECE3;--accent:#4E46E0;--btn:#4E46E0;--btn-deep:#332CB8;--accent-ghost:rgba(78,70,224,.08);--accent-line:rgba(78,70,224,.15);--text:#16150F;--text-dim:#56534A;--ink-prose:#2D2C28;--ink-section:#16150F}
        |*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font-family:Inter,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;font-size:17px;line-height:1.6;font-variant-ligatures:none}
        |a{color:inherit}.site-header{position:sticky;top:0;z-index:100;width:100%;background:var(--bg);border-bottom:1px solid var(--line-soft)}.site-header-inner{display:flex;align-items:center;gap:24px;min-height:60px;max-width:1120px;margin:0 auto;padding:0 28px}.site-header .brand{display:flex;align-items:center;gap:10px;margin:0;color:var(--ink);font-size:20px;font-weight:800;text-decoration:none}.mark{display:block;width:28px;height:28px;border-radius:8px;flex:0 0 28px}.links{display:flex;align-items:center;gap:18px;font-size:15px}.links a{text-decoration:none;color:var(--dim)}.links a:hover{color:var(--accent)}.nav-link{display:flex;align-items:center;gap:7px;white-space:nowrap}.nav-ic,.brand-ic{width:16px;height:16px;flex:0 0 16px}.nav-ic{fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}.brand-ic{fill:currentColor}.right{display:flex;align-items:center;gap:12px;margin-left:auto}.search-wrap{position:relative;width:min(260px,32vw)}.search-input{width:100%;height:36px;border:1px solid var(--line);border-radius:10px;background:var(--surface);color:var(--ink);padding:0 12px;font:inherit;font-size:14px}.search-input:focus{outline:2px solid var(--accent-line);border-color:var(--accent)}.search-results{position:absolute;right:0;top:42px;width:min(360px,88vw);max-height:360px;overflow:auto;border:1px solid var(--line);border-radius:10px;background:var(--surface);box-shadow:0 18px 42px rgba(20,20,15,.14);padding:6px}.search-result{display:block;padding:9px 10px;border-radius:8px;text-decoration:none}.search-result:hover{background:var(--accent-ghost)}.search-result-title{display:block;font-weight:700}.search-result-text{display:block;color:var(--dim);font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.theme-toggle{display:grid;place-items:center;width:36px;height:36px;border:1px solid var(--line);border-radius:10px;background:var(--surface);color:var(--dim);cursor:pointer}.theme-toggle:hover{color:var(--accent);border-color:var(--accent-line)}.theme-toggle svg{width:17px;height:17px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}.theme-toggle .sun{display:none}[data-theme=dark] .theme-toggle .moon{display:none}[data-theme=dark] .theme-toggle .sun{display:block}.version-select{height:36px;border:1px solid var(--line);border-radius:10px;background:var(--surface);color:var(--dim);padding:0 28px 0 10px;font:inherit;font-size:13px}.version-select:focus{outline:2px solid var(--accent-line);border-color:var(--accent)}.btn{border:1px solid var(--line);border-radius:10px;padding:8px 12px;text-decoration:none;font-weight:700;background:var(--surface);font-size:15px}.btn-primary{background:var(--btn);border-color:var(--btn);color:#fff}.btn-primary:hover{background:var(--btn-deep)}
        |.landing{min-height:100vh}.hero{display:grid;grid-template-columns:minmax(0,1fr) minmax(280px,520px);gap:48px;align-items:center;min-height:calc(82vh - 60px);padding:7vw 8vw;border-bottom:1px solid var(--line)}
        |.eyebrow{margin:0 0 12px;color:var(--accent);font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-weight:700;text-transform:uppercase;letter-spacing:.12em;font-size:12px}.hero h1{font-size:clamp(54px,9vw,120px);line-height:.92;margin:0}.lead{font-size:20px;color:var(--dim);max-width:720px}
        |.actions{display:flex;gap:12px;flex-wrap:wrap;margin-top:28px}.button{border:1px solid var(--line);border-radius:10px;padding:10px 14px;text-decoration:none;font-weight:700;background:var(--surface)}.button.primary{background:var(--btn);border-color:var(--btn);color:#fff}.button.primary:hover{background:var(--btn-deep)}
        |.code-card{background:var(--ink-section);border:1px solid rgba(255,255,255,.14);border-radius:12px;overflow:hidden;box-shadow:0 20px 50px rgba(20,20,15,.16)}.code-bar{display:flex;align-items:center;justify-content:space-between;height:38px;padding:0 14px;background:rgba(255,255,255,.06);border-bottom:1px solid rgba(255,255,255,.10)}.code-dots{display:flex;gap:7px}.code-dots span{width:10px;height:10px;border-radius:50%;background:rgba(255,255,255,.26)}.code-lang{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;letter-spacing:.14em;color:#B9B4A8}.code-block{position:relative;margin:22px 0}.code-copy{position:absolute;top:10px;right:10px;z-index:1;display:flex;align-items:center;gap:6px;border:1px solid rgba(255,255,255,.16);border-radius:8px;background:rgba(255,255,255,.08);color:#F4F1EA;padding:5px 8px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;text-transform:uppercase;cursor:pointer}.code-copy svg{width:14px;height:14px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}.code-copy-done{display:none}.code-copy[data-copied=true] .code-copy-idle{display:none}.code-copy[data-copied=true] .code-copy-done{display:inline}.code{background:var(--ink-section);color:#F4F1EA;border:1px solid rgba(255,255,255,.14);border-radius:10px;padding:22px;overflow:auto;box-shadow:0 20px 50px rgba(20,20,15,.16);font-family:ui-monospace,SFMono-Regular,Menlo,monospace;line-height:1.65}.code-card .code{border:0;border-radius:0;box-shadow:none;margin:0}.code-block .code{padding-top:42px}.code code,.code span{font:inherit}.tok-keyword{color:#C792EA}.tok-string{color:#C3E88D}.tok-comment{color:#7E8AA0;font-style:italic}.tok-type{color:#82AAFF}.tok-number{color:#F78C6C}.tok-literal{color:#FF5370}.tok-interpolation{color:#89DDFF}.tok-annotation{color:#FFCB6B}.tok-operator{color:#89DDFF}.module-grid{display:flex;flex-wrap:wrap;justify-content:center;gap:16px;padding:48px 8vw}
        |.module-card{display:flex;flex:1 1 30%;flex-direction:column;gap:8px;min-width:240px;max-width:360px;min-height:176px;padding:18px;border:1px solid var(--line);border-radius:10px;text-decoration:none;background:var(--surface);box-shadow:0 18px 42px rgba(20,20,15,.05)}.module-card strong{min-height:2.5em;line-height:1.25}.module-group,.module-platforms{color:var(--dim)}.module-group{font-size:13px;font-weight:700;text-transform:uppercase;letter-spacing:.08em}.module-summary{flex:1;margin:0;color:var(--dim);font-size:15px;line-height:1.5}.module-platforms{margin-top:auto}
        |.docs{display:grid;grid-template-columns:280px minmax(0,1fr);min-height:calc(100vh - 60px)}.sidebar{position:sticky;top:60px;height:calc(100vh - 60px);overflow:auto;border-right:1px solid var(--line);padding:24px;background:var(--surface)}.sidebar .brand{display:block;font-size:24px;font-weight:800;text-decoration:none;margin-bottom:18px;color:var(--accent)}.nav-group h2{font-size:12px;letter-spacing:.12em;text-transform:uppercase;color:var(--faint);margin:24px 0 8px}.nav-group a,.sidebar>a:not(.brand){display:block;text-decoration:none;padding:6px 0;color:var(--dim)}.nav-group a:hover,.sidebar>a:not(.brand):hover{color:var(--accent)}
        |.article{max-width:940px;padding:56px 7vw;color:var(--ink-prose)}.article h1{font-size:44px;line-height:1.05;color:var(--ink)}.article h2{margin-top:38px;border-top:1px solid var(--line);padding-top:24px;color:var(--ink)}.article h3{color:var(--ink)}.article code{background:var(--accent-ghost);border:1px solid var(--accent-line);border-radius:4px;padding:1px 4px}.article table{display:block;max-width:100%;overflow:auto;border-collapse:collapse;margin:18px 0}.article th,.article td{border:1px solid var(--line);padding:8px 10px;text-align:left}.article th{background:var(--accent-ghost);color:var(--ink)}
        |.article pre code{background:transparent;border:0;border-radius:0;padding:0;color:inherit}.article pre code span{font:inherit}.article pre code .tok-keyword,.article pre code .tok-string,.article pre code .tok-type,.article pre code .tok-number,.article pre code .tok-comment,.article pre code .tok-interpolation,.article pre code .tok-annotation,.article pre code .tok-operator{font:inherit}
        |@media (max-width:780px){.site-header-inner{flex-wrap:wrap;height:auto;padding:8px 16px;gap:8px}.links{order:3;width:100%;gap:14px;overflow:auto}.right{margin-left:0;width:100%;flex-wrap:wrap}.search-wrap{width:100%;order:1}.theme-toggle{order:2}.right .btn{order:3}.ver{display:none}.hero{grid-template-columns:1fr;padding:56px 24px}.docs{grid-template-columns:1fr}.sidebar{position:relative;top:auto;height:auto}.article{padding:32px 24px}}
        |@media (prefers-color-scheme:dark){:root{--bg:#14130D;--surface:#1D1B14;--ink:#F4F1EA;--dim:#B6B1A5;--faint:#8C887C;--line:rgba(255,255,255,.10);--line-soft:rgba(255,255,255,.055);--accent:#9D97F0;--btn:#6E66E8;--btn-deep:#5A52DC;--accent-ghost:rgba(157,151,240,.13);--accent-line:rgba(157,151,240,.24);--text:#E9E5DC;--text-dim:#B6B1A5;--ink-prose:#D6D2C8;--ink-section:#1C1A13}.button{background:var(--surface)}.module-card{box-shadow:none}.article code{color:var(--ink)}}
        |html[data-theme=light]{--bg:#FAF8F4;--surface:#FFFFFF;--ink:#16150F;--dim:#56534A;--faint:#8C887C;--line:#E8E3D9;--line-soft:#F0ECE3;--accent:#4E46E0;--btn:#4E46E0;--btn-deep:#332CB8;--accent-ghost:rgba(78,70,224,.08);--accent-line:rgba(78,70,224,.15);--text:#16150F;--text-dim:#56534A;--ink-prose:#2D2C28;--ink-section:#16150F}
        |html[data-theme=dark]{--bg:#14130D;--surface:#1D1B14;--ink:#F4F1EA;--dim:#B6B1A5;--faint:#8C887C;--line:rgba(255,255,255,.10);--line-soft:rgba(255,255,255,.055);--accent:#9D97F0;--btn:#6E66E8;--btn-deep:#5A52DC;--accent-ghost:rgba(157,151,240,.13);--accent-line:rgba(157,151,240,.24);--text:#E9E5DC;--text-dim:#B6B1A5;--ink-prose:#D6D2C8;--ink-section:#1C1A13}
        |""".stripMargin

object WebsiteMain:

    def main(args: Array[String]): Unit =
        val parsed   = parseArgs(args.toVector)
        val repoRoot = Path.of(parsed.getOrElse("--repo-root", ".")).toAbsolutePath.normalize()
        val out      = Path.of(parsed.getOrElse("--out", "site")).toAbsolutePath.normalize()
        val bundleDir =
            Path.of(parsed.getOrElse("--bundle-dir", discoverBundleDir(repoRoot).toString)).toAbsolutePath.normalize()
        val basePath = parsed.getOrElse("--base-path", "")
        val version  = WebsiteVersion.current(repoRoot)
        val current  = WebsiteContent.fromRepo(repoRoot, version)
        val appended =
            parsed.get("--content").fold(Vector.empty[WebsiteContent])(dir => loadSnapshots(Path.of(dir), version.tag))
        WebsiteGenerator.emit(appended :+ current, out, WebsiteGenerator.Config(repoRoot, bundleDir, basePath))
        println(s"WebsiteMain: wrote $out")

    private def parseArgs(args: Vector[String]): Map[String, String] =
        args.sliding(2, 1).collect { case Vector(flag, value) if flag.startsWith("--") => flag -> value }.toMap

    private def loadSnapshots(contentDir: Path, currentTag: String): Vector[WebsiteContent] =
        if !Files.isDirectory(contentDir) then Vector.empty
        else
            Files
                .list(contentDir)
                .iterator()
                .asScala
                .toVector
                .filter(Files.isDirectory(_))
                .filter(path =>
                    WebsiteVersion.parse(path.getFileName.toString).isDefined && path.getFileName.toString != currentTag
                )
                .sortBy(_.getFileName.toString)
                .map(path =>
                    WebsiteContent.fromRepo(
                        path,
                        WebsiteVersion(
                            path.getFileName.toString,
                            path.getFileName.toString.stripPrefix("v"),
                            latest = false
                        )
                    )
                )

    private def discoverBundleDir(repoRoot: Path): Path =
        val bundleRoot = repoRoot.resolve("out/kymora/website-bundle/js/fullLinkJS.dest")
        if Files.isDirectory(bundleRoot) then bundleRoot
        else repoRoot.resolve("kymora/website-bundle/js/target/scala-3.8.4/kymora-website-bundle-opt")
