package io.eleven19.kymora.website

import kyo.*
import kyo.test.*

class WebsiteContentTests extends Test[Any]:

    "parses grouped module tables from README text" in {
        val content = WebsiteContent.fromReadmes(
            rootReadme,
            WebsiteVersion("v0.1.0", "0.1.0", latest = true),
            readModule
        )

        assert(content.groups.map(_.name) == Vector("Core", "Tooling"))
        assert(content.groups.head.modules.map(_.slug) == Vector("kymora-vfs", "kymora-workflow"))
        assert(content.groups.head.modules.head.title == "kymora-vfs")
        assert(content.groups.head.modules.head.summary == "Virtual filesystem")
        assert(content.groups(1).modules.head.platforms.jvm)
        assert(!content.groups(1).modules.head.platforms.js)
        assert(content.groups.head.modules.head.readme == "# kymora-vfs\n")
    }

    "fails when a README table references missing module content" in {
        val result =
            try
                val _ = WebsiteContent.fromReadmes(
                    rootReadme,
                    WebsiteVersion("v0.1.0", "0.1.0", latest = true),
                    _ => None
                )
                "success"
            catch case e: WebsiteException => e.getMessage

        assert(result.contains("missing README"))
    }

    private def readModule(target: String): Option[String] =
        target match
            case "kymora/vfs/README.md"      => Some("# kymora-vfs\n")
            case "kymora/workflow/README.md" => Some("# kymora-workflow\n")
            case "kymora/kyo/mill/README.md" => Some("# kymora-kyo-mill\n")
            case _                           => None

    private val rootReadme =
        """# kymora
          |
          |## Modules
          |
          |### Core
          |
          || Module | JVM | JS | Native | WASM | Identity |
          || --- | --- | --- | --- | --- | --- |
          || [`kymora-vfs`](kymora/vfs/README.md) | yes | true | y | ✓ | Virtual filesystem |
          || [kymora-workflow](kymora/workflow/README.md) | ✅ | ✅ | ✅ | ✅ | Workflow engine |
          |
          |### Tooling
          |
          || Module | JVM | JS | Native | WASM | Identity |
          || --- | --- | --- | --- | --- | --- |
          || [kymora-kyo-mill](kymora/kyo/mill/README.md) | ✅ | ❌ | ❌ | ❌ | Mill plugin |
          |""".stripMargin
