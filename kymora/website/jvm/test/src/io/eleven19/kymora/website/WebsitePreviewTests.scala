package io.eleven19.kymora.website

import java.net.URI
import java.nio.file.Files
import kyo.*
import kyo.test.*

class WebsitePreviewTests extends Test[Any]:

    "maps clean documentation routes to generated index files" in {
        val root = Files.createTempDirectory("kymora-website-preview")
        val file = WebsitePreview.resolve(root, URI.create("/kymora/latest/kymora-vfs/"), "/kymora")

        assert(file.equals(root.resolve("latest/kymora-vfs/index.html")))
    }

    "prevents preview requests from escaping the generated site root" in {
        val root = Files.createTempDirectory("kymora-website-preview-escape")
        val file = WebsitePreview.resolve(root, URI.create("/kymora/%2e%2e/secret"), "/kymora")

        assert(file.equals(root.resolve("404.html")))
    }
