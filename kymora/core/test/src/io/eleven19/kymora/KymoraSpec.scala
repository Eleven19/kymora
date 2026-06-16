package io.eleven19.kymora

import zio.test.*

object KymoraSpec extends ZIOSpecDefault:
    def spec = suite("KymoraSpec")(
        test("name is kymora") {
            assertTrue(Kymora.name == "kymora")
        }
    )
