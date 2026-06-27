package io.eleven19.kymora.website

final class WebsiteException(message: String, cause: Throwable | Null = null) extends RuntimeException(message, cause)

final case class WebsiteVersion(tag: String, label: String, latest: Boolean) derives CanEqual

object WebsiteVersion:

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

    def fromReadmes(
        readme: String,
        version: WebsiteVersion,
        readModule: String => Option[String]
    ): WebsiteContent =
        WebsiteContent(readme, parseGroups(readme, readModule), version)

    private def parseGroups(readme: String, readModule: String => Option[String]): Vector[Group] =
        sectionBody(readme, "## Modules") match
            case None => Vector.empty
            case Some(body) =>
                splitGroups(body).map { case (name, lines) =>
                    val rows     = lines.filter(_.trim.startsWith("|"))
                    val dataRows = rows.filterNot(isSeparatorRow).drop(1)
                    val modules  = dataRows.flatMap(row => parseModule(name, row, readModule))
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

    private def parseModule(
        group: String,
        row: String,
        readModule: String => Option[String]
    ): Option[WebsiteModule] =
        val cells = pipeCells(row)
        if cells.size < 5 then throw new WebsiteException(s"malformed module table row: $row")
        val link = parseLink(cells.head).getOrElse(throw new WebsiteException(s"malformed module link: ${cells.head}"))
        if !link.target.endsWith("README.md") then None
        else
            val readme =
                readModule(link.target).getOrElse(throw new WebsiteException(s"missing README: ${link.target}"))
            Some(
                WebsiteModule(
                    slug = slug(link.label),
                    group = group,
                    title = link.label,
                    target = link.target,
                    readme = readme,
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
