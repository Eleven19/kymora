package io.eleven19.kymora.workflow.store

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.vfs.{Vfs, VPath}
import kyo.*
import kyo.test.*

class VfsDirStoreTests extends Test[Any]:
  "write then read returns the same bytes" in {
    val root = VPath("cache")
    for
      vfs   <- Vfs.inMemory.init
      store <- VfsDirStore.init(root, vfs)
      key    = CacheKey("kymora/vfs/jvm/compile")
      bytes  = Chunk.from("hello".getBytes)
      _     <- store.write(key, bytes, destSeal = Maybe.empty)
      read  <- store.read(key)
    yield
      assert(read.isDefined)
      assert(read.exists(_.bytes == bytes))
  }
  "read on a missing key returns Maybe.empty" in {
    val root = VPath("cache")
    for
      vfs   <- Vfs.inMemory.init
      store <- VfsDirStore.init(root, vfs)
      r     <- store.read(CacheKey("none"))
    yield assert(r.isEmpty)
  }
  "write overwrites a prior manifest for the same key" in {
    val root = VPath("cache")
    for
      vfs   <- Vfs.inMemory.init
      store <- VfsDirStore.init(root, vfs)
      key    = CacheKey("kymora/vfs/jvm/compile")
      b1     = Chunk.from("first".getBytes)
      b2     = Chunk.from("second".getBytes)
      _     <- store.write(key, b1, destSeal = Maybe.empty)
      _     <- store.write(key, b2, destSeal = Maybe.empty)
      read  <- store.read(key)
    yield
      assert(read.exists(_.bytes == b2))
  }
end VfsDirStoreTests
