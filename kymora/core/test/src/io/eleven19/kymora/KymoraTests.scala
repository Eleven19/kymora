package io.eleven19.kymora

import kyo.*
import kyo.test.*

class KymoraTests extends Test[Any]:
    "name is kymora" in {
        assert(Kymora.name == "kymora")
    }
end KymoraTests