package io.eleven19.kymora.vfs

import kyo.*
import kyo.test.*

class VfsTests extends Test[Any]:
    "name is kymora-vfs" in {
        assert(Vfs.name == "kymora-vfs")
    }
end VfsTests
