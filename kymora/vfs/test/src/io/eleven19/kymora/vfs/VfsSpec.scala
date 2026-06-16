package io.eleven19.kymora.vfs

import zio.test.*

object VfsSpec extends ZIOSpecDefault:
    def spec = suite("VfsSpec")(
        test("name is kymora-vfs") {
            assertTrue(Vfs.name == "kymora-vfs")
        }
    )
