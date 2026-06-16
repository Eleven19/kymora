package io.eleven19.kymora.vfs

import kyo.*
import kyo.test.*

class VfsErrorTests extends Test[Any]:
    "errors include path and operation context" in {
        val path = VPath.root / "config" / "app.conf"
        assert(VfsError.NotFound(path).getMessage.contains("/config/app.conf"))
        assert(VfsError.Unsupported(path, "createSymlink").getMessage.contains("createSymlink"))
    }

    "backend failures preserve cause" in {
        val cause = new IllegalStateException("boom")
        val error = VfsError.BackendFailure(VPath.root / "data.db", "read", cause)
        assert(error.getCause.eq(cause))
    }
end VfsErrorTests
