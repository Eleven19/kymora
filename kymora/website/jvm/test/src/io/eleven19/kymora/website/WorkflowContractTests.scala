package io.eleven19.kymora.website

import java.nio.file.Files
import java.nio.file.Path
import kyo.*
import kyo.test.*

class WorkflowContractTests extends Test[Any]:

    "deploy workflow publishes Pages from release or manual dispatch only" in {
        val text = Files.readString(repoRoot.resolve(".github/workflows/deploy-site.yml"))
        assert(text.contains("release:"))
        assert(text.contains("types: [published]"))
        assert(text.contains("workflow_dispatch"))
        assert(text.contains("pages: write"))
        assert(text.contains("id-token: write"))
        assert(text.contains("actions/configure-pages"))
        assert(text.contains("actions/upload-pages-artifact"))
        assert(text.contains("actions/deploy-pages"))
        assert(text.contains("kymora.website-bundle.js.fullLinkJS"))
        assert(text.contains("kymora.website.jvm.run"))
        assert(!runShell(text).contains("git push"))
        assert(!runShell(text).contains("git commit"))
        assert(!text.contains("branches: [main]"))
    }

    "readme workflow validates public READMEs through the Kymora Mill doctest module" in {
        val text = Files.readString(repoRoot.resolve(".github/workflows/readme.yml"))
        assert(text.contains("pull_request:"))
        assert(text.contains("branches: [main]"))
        assert(text.contains("./mill kymora.docs.jvm.doctest"))
    }

    "browser bundle wires theme, search, and code copy enhancements" in {
        val text = Files.readString(
            repoRoot.resolve("kymora/website-bundle/js/src/io/eleven19/kymora/website/WebsiteBundleMain.scala")
        )
        assert(text.contains("theme-toggle"))
        assert(text.contains("localStorage"))
        assert(text.contains("search-index.json"))
        assert(text.contains("search-results"))
        assert(text.contains("code-copy"))
        assert(text.contains("clipboard"))
        assert(text.contains("data-copied"))
        assert(text.contains("version-select"))
        assert(text.contains("location.href"))
    }

    private def repoRoot: Path =
        var current = Path.of(java.lang.System.getProperty("user.dir")).toAbsolutePath
        while current != null && !Files.exists(current.resolve("build.mill.yaml")) do current = current.getParent
        if current == null then throw new RuntimeException("repo root not found")
        else current

    private def runShell(yaml: String): String =
        yaml.linesIterator.filter(line => line.trim.startsWith("run:")).mkString("\n")
