package io.eleven19.kymora.website

import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*

object WebsiteBundleMain:

    final private case class SearchEntry(title: String, href: String, text: String) derives CanEqual

    private val ThemeKey = "kymora-theme"

    def main(args: Array[String]): Unit =
        val _ = args
        Option(dom.document.documentElement).foreach(_.classList.add("kymora-js"))
        applyStoredTheme()
        wireThemeToggle()
        wireVersionSelect()
        wireSearch()
        wireCodeCopy()

    private def applyStoredTheme(): Unit =
        val stored =
            Option(dom.window.localStorage.getItem(ThemeKey)).filter(theme => theme == "dark" || theme == "light")
        val dark = dom.window.matchMedia("(prefers-color-scheme: dark)").matches
        setTheme(stored.getOrElse(if dark then "dark" else "light"), persist = false)

    private def wireThemeToggle(): Unit =
        dom.document.querySelectorAll(".theme-toggle").foreach { node =>
            node.addEventListener(
                "click",
                _ =>
                    val next = Option(dom.document.documentElement.getAttribute("data-theme")) match
                        case Some("dark") => "light"
                        case _            => "dark"
                    setTheme(next, persist = true)
            )
        }

    private def setTheme(theme: String, persist: Boolean): Unit =
        val root = dom.document.documentElement
        root.setAttribute("data-theme", theme)
        root.asInstanceOf[js.Dynamic].style.colorScheme = theme
        if persist then dom.window.localStorage.setItem(ThemeKey, theme)

    private def wireVersionSelect(): Unit =
        dom.document.querySelectorAll(".version-select").foreach { node =>
            val select = node.asInstanceOf[dom.html.Select]
            select.addEventListener(
                "change",
                _ => if select.value.nonEmpty then dom.window.location.href = select.value
            )
        }

    private def wireSearch(): Unit =
        dom.document.querySelectorAll(".search-wrap").foreach { node =>
            val wrap    = node.asInstanceOf[dom.Element]
            val input   = wrap.querySelector(".search-input").asInstanceOf[dom.html.Input]
            val results = wrap.querySelector(".search-results").asInstanceOf[dom.html.Div]
            val url     = Option(wrap.getAttribute("data-search-index")).getOrElse("latest/search-index.json")
            val base    = basePath(url)
            var loaded  = false
            var entries = Vector.empty[SearchEntry]

            def update(): Unit =
                renderSearch(input.value, entries, base, results)

            def ensureLoaded(): Unit =
                if !loaded then
                    loaded = true
                    dom.Fetch
                        .fetch(url)
                        .toFuture
                        .flatMap(_.text().toFuture)
                        .foreach { json =>
                            entries = parseEntries(json)
                            update()
                        }

            input.addEventListener(
                "focus",
                _ => ensureLoaded()
            )
            input.addEventListener(
                "input",
                _ =>
                    ensureLoaded()
                    update()
            )
        }

        dom.document.addEventListener(
            "click",
            event =>
                val insideSearch = closest(event.target, ".search-wrap").isDefined
                if !insideSearch then
                    dom.document.querySelectorAll(".search-results").foreach { node =>
                        hide(node.asInstanceOf[dom.html.Div])
                    }
        )

    private def renderSearch(query: String, entries: Vector[SearchEntry], base: String, results: dom.html.Div): Unit =
        val q = query.trim.toLowerCase(java.util.Locale.ROOT)
        if q.isEmpty then
            results.innerHTML = ""
            hide(results)
        else
            val hits = entries
                .filter(entry =>
                    entry.title
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains(q) || entry.text.toLowerCase(java.util.Locale.ROOT).contains(q)
                )
                .take(8)
            results.innerHTML =
                if hits.isEmpty then """<div class="search-empty">No results</div>"""
                else
                    hits
                        .map(entry =>
                            val href = if entry.href.startsWith("/") then base + entry.href else entry.href
                            s"""<a class="search-result" href="${escapeAttr(
                                    href
                                )}"><span class="search-result-title">${escapeHtml(
                                    entry.title
                                )}</span><span class="search-result-text">${escapeHtml(
                                    snippet(entry.text, q)
                                )}</span></a>"""
                        )
                        .mkString
            show(results)

    private def parseEntries(json: String): Vector[SearchEntry] =
        try
            val parsed = js.JSON.parse(json).asInstanceOf[js.Dynamic]
            parsed.entries.asInstanceOf[js.Array[js.Dynamic]].toVector.map { item =>
                SearchEntry(
                    Option(item.title.asInstanceOf[String]).getOrElse(""),
                    Option(item.href.asInstanceOf[String]).getOrElse(""),
                    Option(item.text.asInstanceOf[String]).getOrElse("")
                )
            }
        catch case _: Throwable => Vector.empty

    private def basePath(url: String): String =
        val marker = "/latest/"
        val idx    = url.indexOf(marker)
        if idx <= 0 then "" else url.take(idx)

    private def hide(element: dom.Element): Unit =
        element.setAttribute("hidden", "")

    private def show(element: dom.Element): Unit =
        element.removeAttribute("hidden")

    private def snippet(text: String, query: String): String =
        val clean = text.replaceAll("\\s+", " ").trim
        val idx   = clean.toLowerCase(java.util.Locale.ROOT).indexOf(query)
        val start = if idx < 0 then 0 else math.max(0, idx - 36)
        clean.slice(start, math.min(clean.length, start + 120))

    private def wireCodeCopy(): Unit =
        dom.document.addEventListener(
            "click",
            event =>
                closest(event.target, ".code-copy").foreach { node =>
                    val button = node.asInstanceOf[dom.html.Button]
                    closest(button, ".code-block").foreach { block =>
                        Option(block.querySelector("pre code")).foreach { code =>
                            val text = code.textContent
                            copyToClipboard(text)
                            button.setAttribute("data-copied", "true")
                            dom.window.setTimeout(() => button.removeAttribute("data-copied"), 1600)
                        }
                    }
                }
        )

    private def copyToClipboard(text: String): Unit =
        val clipboard = dom.window.navigator.asInstanceOf[js.Dynamic].clipboard
        if js.isUndefined(clipboard) || js.isUndefined(clipboard.writeText) then fallbackCopy(text)
        else
            val _ = clipboard.writeText(text).asInstanceOf[js.Promise[Unit]]

    private def fallbackCopy(text: String): Unit =
        val area = dom.document.createElement("textarea").asInstanceOf[dom.html.TextArea]
        area.value = text
        area.setAttribute("readonly", "true")
        area.style.position = "fixed"
        area.style.left = "-9999px"
        val _ = dom.document.body.appendChild(area)
        area.select()
        val _ = dom.document.execCommand("copy")
        val _ = dom.document.body.removeChild(area)

    private def closest(target: dom.EventTarget | Null, selector: String): Option[dom.Element] =
        target match
            case element: dom.Element => Option(element.closest(selector))
            case _                    => None

    private def escapeHtml(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private def escapeAttr(text: String): String =
        escapeHtml(text).replace("'", "&#39;")
