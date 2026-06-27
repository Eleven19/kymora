package io.eleven19.kymora.website

import java.nio.file.Files
import java.nio.file.Path
import kyo.*
import kyo.test.*

class WebsiteBuildGraphTests extends Test[Any]:

    "keeps JVM-only markdown rendering dependencies out of shared and JS website sources" in {
        val root   = repoRoot
        val shared = scalaFiles(root.resolve("kymora/website/shared"))
        val js = scalaFiles(root.resolve("kymora/website/js")) ++ scalaFiles(root.resolve("kymora/website-bundle/js"))

        assert(!shared.exists(path => Files.readString(path).contains("scala.meta")))
        assert(!js.exists(path => Files.readString(path).contains("scala.meta")))
    }

    private def repoRoot: Path =
        var current = Path.of(java.lang.System.getProperty("user.dir")).toAbsolutePath
        while current != null && !Files.exists(current.resolve("build.mill.yaml")) do current = current.getParent
        if current == null then throw new RuntimeException("repo root not found")
        else current

    private def scalaFiles(root: Path): Vector[Path] =
        if !Files.exists(root) then Vector.empty
        else
            val stream = Files.walk(root)
            try
                stream
                    .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala"))
                    .toArray
                    .toVector
                    .map(_.asInstanceOf[Path])
            finally stream.close()
