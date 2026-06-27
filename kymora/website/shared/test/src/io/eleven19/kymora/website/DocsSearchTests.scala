package io.eleven19.kymora.website

import kyo.*
import kyo.test.*

class DocsSearchTests extends Test[Any]:

    "builds a search index from overview and module content" in {
        val content = WebsiteContent(
            intro = "# Kymora\n\nWorkflow documentation overview",
            groups = Vector(
                WebsiteContent.Group(
                    "Core",
                    Vector(
                        WebsiteModule(
                            slug = "kymora-vfs",
                            group = "Core",
                            title = "kymora-vfs",
                            target = "kymora/vfs/README.md",
                            readme = "# kymora-vfs\n\nVirtual filesystem adapters",
                            summary = "Virtual filesystem",
                            platforms = WebsiteModule.Platforms(jvm = true, js = true, native = true, wasm = true)
                        )
                    )
                )
            ),
            version = WebsiteVersion("v0.1.0", "0.1.0", latest = true)
        )

        val index = DocsSearch.indexFor("latest", content)

        assert(index.entries.map(_.title) == Vector("Overview", "kymora-vfs"))
        assert(index.entries.map(_.href) == Vector("/latest/", "/latest/kymora-vfs/"))
        assert(index.entries(1).text.contains("Virtual filesystem adapters"))
    }

    "ranks title hits before body hits and reports empty searches" in {
        val entries = Vector(
            DocsSearch.Entry("Workflow", "/latest/kymora-workflow/", "execution graph"),
            DocsSearch.Entry("VFS", "/latest/kymora-vfs/", "workflow cache storage")
        )

        val hits = DocsSearch.filter(entries, "workflow")

        assert(hits.map(_.href) == Vector("/latest/kymora-workflow/", "/latest/kymora-vfs/"))
        assert(DocsSearch.filter(entries, "missing").isEmpty)
        assert(DocsSearch.filter(entries, "   ").isEmpty)
    }
