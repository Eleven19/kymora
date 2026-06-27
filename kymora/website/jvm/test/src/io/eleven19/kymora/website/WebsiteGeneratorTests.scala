package io.eleven19.kymora.website

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kyo.*
import kyo.test.*

class WebsiteGeneratorTests extends Test[Any]:

    "emits a Pages-ready static site without a CNAME" in {
        val root      = Files.createTempDirectory("kymora-website-repo")
        val bundleDir = Files.createTempDirectory("kymora-website-bundle")
        val out       = Files.createTempDirectory("kymora-website-out")
        write(root.resolve("README.md"), rootReadme)
        writeBrandAssets(root)
        write(
            root.resolve("kymora/vfs/README.md"),
            """# kymora-vfs
              |
              |See [Kyo](https://github.com/getkyo/kyo).
              |
              |- Host-backed filesystems
              |- In-memory filesystems
              |  with mounted overlays
              |
              |```scala
              |val program: String =
              |  "hello"
              |```
              |
              |```console
              |$ export KYMORA_MODE="docs"
              |$ ./mill --meta-level 1 kymora.website.jvm.test # run website tests
              |```
              |
              |```sh
              |# macOS (Homebrew)
              |brew install jj
              |# or via cargo
              |cargo install --locked jj-cli
              |jj edit <change-id-of-A> # jump back
              |jj git clone --colocate git@github.com:Eleven19/kymora.git
              |```
              |
              || Mode | JVM |
              || --- | --- |
              || Memory | yes |
              |""".stripMargin
        )
        write(bundleDir.resolve("main.js"), "console.log('kymora')")
        write(bundleDir.resolve("main.js.map"), "{}")

        val content = WebsiteContentLoader.fromRepo(root, WebsiteVersion("v0.1.0", "0.1.0", latest = true))
        WebsiteGenerator.emit(
            Seq(content),
            out,
            WebsiteGenerator.Config(repoRoot = root, bundleDir = bundleDir, basePath = "/kymora")
        )

        assert(Files.exists(out.resolve("index.html")))
        assert(Files.exists(out.resolve("latest/index.html")))
        assert(Files.exists(out.resolve("latest/kymora-vfs/index.html")))
        assert(Files.exists(out.resolve("latest/manifest.json")))
        assert(Files.exists(out.resolve("latest/search-index.json")))
        assert(Files.exists(out.resolve("versions.json")))
        assert(Files.exists(out.resolve("sitemap.xml")))
        assert(Files.exists(out.resolve("robots.txt")))
        assert(Files.exists(out.resolve(".nojekyll")))
        assert(Files.exists(out.resolve("main.js")))
        assert(Files.exists(out.resolve("kymora.svg")))
        assert(Files.exists(out.resolve("kymora-icon.svg")))
        assert(!Files.exists(out.resolve("CNAME")))
        val homePage = Files.readString(out.resolve("index.html"))
        assert(homePage.contains("Kymora"))
        assert(homePage.contains("src=\"/kymora/main.js\""))
        assert(homePage.contains("""<link rel="icon" type="image/svg+xml" href="/kymora/kymora-icon.svg">"""))
        assert(homePage.contains("""<header class="site-header" data-section="header">"""))
        assert(homePage.contains("""<a class="brand" data-role="logo" href="/kymora/">"""))
        assert(homePage.contains("""<img class="mark" src="/kymora/kymora-icon.svg" alt="Kymora">"""))
        assert(homePage.contains("""<nav class="links">"""))
        assert(homePage.contains("""<a class="nav-link" href="/kymora/latest/">"""))
        assert(homePage.contains("""<svg class="nav-ic" viewBox="0 0 24 24" aria-hidden="true">"""))
        assert(homePage.contains("""<span>Docs</span>"""))
        assert(
            homePage.contains("""<a class="nav-link" href="https://javadoc.io/doc/io.eleven19.kymora/kymora-vfs_3">""")
        )
        assert(homePage.contains("""<span>API</span>"""))
        assert(homePage.contains("""<a class="nav-link soc" href="https://github.com/Eleven19/kymora">"""))
        assert(homePage.contains("""<svg class="brand-ic" viewBox="0 0 24 24" aria-hidden="true">"""))
        assert(homePage.contains("""<div class="search-wrap" data-search-index="/kymora/latest/search-index.json">"""))
        assert(homePage.contains("<input class=\"search-input\" type=\"search\" placeholder=\"Search docs\""))
        assert(homePage.contains("""<div class="search-results" hidden></div>"""))
        assert(homePage.contains("""<button class="theme-toggle" type="button" aria-label="Toggle dark mode">"""))
        assert(
            homePage.contains(
                """<select class="version-select" aria-label="Select documentation version">"""
            )
        )
        assert(homePage.contains("""<option value="/kymora/latest/" selected>latest</option>"""))
        assert(homePage.contains("""<option value="/kymora/v0.1.0/">0.1.0</option>"""))
        assert(homePage.contains("""<div class="code-card" data-section="signature">"""))
        assert(homePage.contains("""<div class="code-bar"><div class="code-dots">"""))
        assert(homePage.contains("""<span class="code-lang">SCALA</span>"""))
        assert(homePage.contains("--bg:#FAF8F4"))
        assert(homePage.contains("--accent:#4E46E0"))
        assert(homePage.contains("--ink-section:#16150F"))
        assert(homePage.contains("""<span class="module-group">Core</span>"""))
        assert(homePage.contains("""<p class="module-summary">Virtual filesystem</p>"""))
        assert(homePage.contains("""<small class="module-platforms">JVM · JS · Native · WASM</small>"""))
        assert(homePage.contains(".module-grid{display:flex;flex-wrap:wrap;justify-content:center"))
        assert(homePage.contains("@media (prefers-color-scheme:dark)"))
        val modulePage  = Files.readString(out.resolve("latest/kymora-vfs/index.html"))
        val versionPage = Files.readString(out.resolve("v0.1.0/index.html"))
        assert(versionPage.contains("""<option value="/kymora/v0.1.0/" selected>0.1.0</option>"""))
        assert(versionPage.contains("""<p><img src="/kymora/kymora.svg" alt="Kymora"></p>"""))
        assert(versionPage.contains("""<ul><li>Install <code>jj</code>:</li></ul>"""))
        assert(versionPage.contains("""<pre class="code" data-lang="sh"><code>"""))
        assert(versionPage.contains("""<span class="tok-comment"># macOS (Homebrew)</span>"""))
        assert(
            versionPage.contains(
                """<span class="tok-keyword">brew</span> install <span class="tok-keyword">jj</span>"""
            )
        )
        assert(!versionPage.contains("""<p>```sh"""))
        assert(modulePage.contains("""<a href="https://github.com/getkyo/kyo">Kyo</a>"""))
        assert(
            modulePage.contains(
                """<div class="code-block"><button class="code-copy" type="button" aria-label="Copy code to clipboard">"""
            )
        )
        assert(modulePage.contains("""<svg class="code-copy-glyph" viewBox="0 0 24 24" aria-hidden="true">"""))
        assert(
            modulePage.contains(
                """<span class="code-copy-idle">Copy</span><span class="code-copy-done">Copied</span>"""
            )
        )
        assert(modulePage.contains("""<pre class="code" data-lang="scala"><code>"""))
        assert(modulePage.contains("""<span class="tok-keyword">val</span>"""))
        assert(modulePage.contains("""<span class="tok-type">String</span>"""))
        assert(modulePage.contains("""<span class="tok-string">&quot;hello&quot;</span>"""))
        assert(modulePage.contains("""<pre class="code" data-lang="console"><code>"""))
        assert(modulePage.contains("""<span class="tok-operator">$</span>"""))
        assert(modulePage.contains("""<span class="tok-keyword">export</span>"""))
        assert(modulePage.contains("""<span class="tok-interpolation">KYMORA_MODE</span>"""))
        assert(modulePage.contains("""<span class="tok-string">&quot;docs&quot;</span>"""))
        assert(modulePage.contains("""<span class="tok-annotation">--meta-level</span>"""))
        assert(modulePage.contains("""<span class="tok-comment"># run website tests</span>"""))
        assert(modulePage.contains("""<pre class="code" data-lang="sh"><code>"""))
        assert(modulePage.contains("""<span class="tok-comment"># macOS (Homebrew)</span>"""))
        assert(modulePage.contains("""<span class="tok-comment"># or via cargo</span>"""))
        assert(!modulePage.contains("""<span class="tok-operator">#</span> macOS"""))
        assert(modulePage.contains("""&lt;change-id-of-A&gt;"""))
        assert(modulePage.contains("""<span class="tok-comment"># jump back</span>"""))
        assert(modulePage.contains("""--colocate</span> git@github.com:Eleven19/kymora.git"""))
        assert(
            modulePage.contains(
                "<ul><li>Host-backed filesystems</li><li>In-memory filesystems with mounted overlays</li></ul>"
            )
        )
        assert(modulePage.contains("<table><thead><tr><th>Mode</th><th>JVM</th></tr></thead>"))
        assert(Files.readString(out.resolve("sitemap.xml")).contains("/kymora/latest/kymora-vfs/"))
        assert(Files.readString(out.resolve("kymora.svg")).contains("<title>Kymora logo</title>"))
        assert(Files.readString(out.resolve("kymora-icon.svg")).contains("<title>Kymora icon</title>"))
    }

    "fails loud when the browser bundle is missing" in {
        val root      = Files.createTempDirectory("kymora-website-repo-missing-bundle")
        val bundleDir = Files.createTempDirectory("kymora-website-empty-bundle")
        val out       = Files.createTempDirectory("kymora-website-out-missing-bundle")
        write(root.resolve("README.md"), rootReadme)
        writeBrandAssets(root)
        write(root.resolve("kymora/vfs/README.md"), "# kymora-vfs\n")

        val content = WebsiteContentLoader.fromRepo(root, WebsiteVersion("v0.1.0", "0.1.0", latest = true))
        val result =
            try
                WebsiteGenerator.emit(
                    Seq(content),
                    out,
                    WebsiteGenerator.Config(repoRoot = root, bundleDir = bundleDir, basePath = "")
                )
                "success"
            catch case e: WebsiteException => e.getMessage

        assert(result.contains("missing bundle"))
    }

    private val rootReadme =
        """![Kymora](kymora.svg)
          |
          |# kymora
          |
          |## Modules
          |
          |### Core
          |
          || Module | JVM | JS | Native | WASM | Identity |
          || --- | --- | --- | --- | --- | --- |
          || [`kymora-vfs`](kymora/vfs/README.md) | ✅ | ✅ | ✅ | ✅ | Virtual filesystem |
          |
          |## Developing
          |
          |- Install `jj`:
          |
          |  ```sh
          |  # macOS (Homebrew)
          |  brew install jj
          |  ```
          |""".stripMargin

    private def write(path: java.nio.file.Path, text: String): Unit =
        Files.createDirectories(path.getParent)
        val _ = Files.writeString(path, text, StandardCharsets.UTF_8)

    private def writeBrandAssets(root: java.nio.file.Path): Unit =
        write(root.resolve("kymora.svg"), """<svg><title>Kymora logo</title></svg>""")
        write(root.resolve("kymora-icon.svg"), """<svg><title>Kymora icon</title></svg>""")
