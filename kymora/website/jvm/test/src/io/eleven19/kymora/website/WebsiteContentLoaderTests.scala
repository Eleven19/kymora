package io.eleven19.kymora.website

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kyo.*
import kyo.test.*

class WebsiteContentLoaderTests extends Test[Any]:

    "loads website content from the repository filesystem" in {
        val root = Files.createTempDirectory("kymora-website-content")
        write(root.resolve("README.md"), rootReadme)
        write(root.resolve("kymora/vfs/README.md"), "# kymora-vfs\n")

        val content = WebsiteContentLoader.fromRepo(root, WebsiteVersion("v0.1.0", "0.1.0", latest = true))

        assert(content.groups.head.modules.head.readme == "# kymora-vfs\n")
    }

    private val rootReadme =
        """# kymora
          |
          |## Modules
          |
          |### Core
          |
          || Module | JVM | JS | Native | WASM | Identity |
          || --- | --- | --- | --- | --- | --- |
          || [kymora-vfs](kymora/vfs/README.md) | ✅ | ✅ | ✅ | ✅ | Virtual filesystem |
          |""".stripMargin

    private def write(path: java.nio.file.Path, text: String): Unit =
        Files.createDirectories(path.getParent)
        val _ = Files.writeString(path, text, StandardCharsets.UTF_8)
