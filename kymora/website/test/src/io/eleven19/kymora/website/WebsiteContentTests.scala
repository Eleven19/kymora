package io.eleven19.kymora.website

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kyo.*
import kyo.test.*

class WebsiteContentTests extends Test[Any]:

    "parses grouped module tables from the root README" in {
        val root = Files.createTempDirectory("kymora-website-content")
        write(
            root.resolve("README.md"),
            """# kymora
              |
              |## Modules
              |
              |### Core
              |
              || Module | JVM | JS | Native | WASM | Identity |
              || --- | --- | --- | --- | --- | --- |
              || [`kymora-vfs`](kymora/vfs/README.md) | ✅ | ✅ | ✅ | ✅ | Virtual filesystem |
              || [kymora-workflow](kymora/workflow/README.md) | ✅ | ✅ | ✅ | ✅ | Workflow engine |
              |
              |### Tooling
              |
              || Module | JVM | JS | Native | WASM | Identity |
              || --- | --- | --- | --- | --- | --- |
              || [kymora-kyo-mill](kymora/kyo/mill/README.md) | ✅ | ❌ | ❌ | ❌ | Mill plugin |
              |""".stripMargin
        )
        write(root.resolve("kymora/vfs/README.md"), "# kymora-vfs\n")
        write(root.resolve("kymora/workflow/README.md"), "# kymora-workflow\n")
        write(root.resolve("kymora/kyo/mill/README.md"), "# kymora-kyo-mill\n")

        val content = WebsiteContent.fromRepo(root, WebsiteVersion("v0.1.0", "0.1.0", latest = true))

        assert(content.groups.map(_.name) == Vector("Core", "Tooling"))
        assert(content.groups.head.modules.map(_.slug) == Vector("kymora-vfs", "kymora-workflow"))
        assert(content.groups.head.modules.head.title == "kymora-vfs")
        assert(content.groups.head.modules.head.summary == "Virtual filesystem")
        assert(content.groups(1).modules.head.platforms.jvm)
        assert(!content.groups(1).modules.head.platforms.js)
        assert(content.groups.head.modules.head.readme == "# kymora-vfs\n")
    }

    "fails when a README table references a missing module README" in {
        val root = Files.createTempDirectory("kymora-website-missing")
        write(
            root.resolve("README.md"),
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
        )

        val result =
            try
                val _ = WebsiteContent.fromRepo(root, WebsiteVersion("v0.1.0", "0.1.0", latest = true))
                "success"
            catch case e: WebsiteException => e.getMessage

        assert(result.contains("missing README"))
    }

    private def write(path: java.nio.file.Path, text: String): Unit =
        Files.createDirectories(path.getParent)
        val _ = Files.writeString(path, text, StandardCharsets.UTF_8)
