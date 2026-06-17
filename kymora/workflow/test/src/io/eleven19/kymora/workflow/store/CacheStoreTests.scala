package io.eleven19.kymora.workflow.store

import io.eleven19.kymora.workflow.*
import kyo.*
import kyo.test.*

class CacheStoreTests extends Test[Any]:
  "CacheKey reads its raw value back" in {
    assert(CacheKey("kymora/vfs/jvm/compile").value == "kymora/vfs/jvm/compile")
  }
  "CacheKey can be constructed from a TaskId" in {
    val k = CacheKey.fromTaskId(TaskId("kymora.vfs.jvm.compile"))
    assert(k.value == "kymora/vfs/jvm/compile")
  }
end CacheStoreTests
