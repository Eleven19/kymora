package io.eleven19.kymora.website

object DocsSearch:

    final case class Entry(title: String, href: String, text: String) derives CanEqual
    final case class Index(entries: Vector[Entry]) derives CanEqual

    def indexFor(prefix: String, content: WebsiteContent): Index =
        val normalizedPrefix = prefix.stripPrefix("/").stripSuffix("/")
        val overview = Entry(
            title = "Overview",
            href = s"/$normalizedPrefix/",
            text = stripMarkdown(content.intro)
        )
        val modules = content.groups.flatMap(_.modules).map { module =>
            Entry(
                title = module.title,
                href = s"/$normalizedPrefix/${module.slug}/",
                text = stripMarkdown(module.readme)
            )
        }
        Index(overview +: modules)

    def filter(entries: Vector[Entry], query: String): Vector[Entry] =
        val terms = query.trim.toLowerCase(java.util.Locale.ROOT).split("\\s+").toVector.filter(_.nonEmpty)
        if terms.isEmpty then Vector.empty
        else
            entries
                .map(entry => entry -> score(entry, terms))
                .filter(_._2 > 0)
                .sortBy { case (entry, score) => (-score, entry.title.toLowerCase(java.util.Locale.ROOT)) }
                .map(_._1)

    def stripMarkdown(text: String): String =
        text.linesIterator
            .filterNot(_.trim.startsWith("#"))
            .map(_.replaceAll("[`*_>#\\[\\]()]+", " "))
            .mkString(" ")
            .replaceAll("\\s+", " ")
            .trim

    private def score(entry: Entry, terms: Vector[String]): Int =
        val title = entry.title.toLowerCase(java.util.Locale.ROOT)
        val text  = entry.text.toLowerCase(java.util.Locale.ROOT)
        terms.foldLeft(0) { (sum, term) =>
            if title.contains(term) then sum + 100
            else if text.contains(term) then sum + 10
            else sum
        }
