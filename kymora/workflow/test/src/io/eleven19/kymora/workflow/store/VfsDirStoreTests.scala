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
  "purge wipes the cache root" in {
    val root = VPath("cache")
    for
      vfs   <- Vfs.inMemory.init
      store <- VfsDirStore.init(root, vfs)
      _     <- store.write(CacheKey("a"), Chunk.from("x".getBytes), Maybe.empty)
      _     <- store.write(CacheKey("b/c"), Chunk.from("y".getBytes), Maybe.empty)
      _     <- store.purge()
      ra    <- store.read(CacheKey("a"))
      rb    <- store.read(CacheKey("b/c"))
    yield
      assert(ra.isEmpty)
      assert(rb.isEmpty)
  }
  "remove deletes a single manifest" in {
    val root = VPath("cache")
    for
      vfs   <- Vfs.inMemory.init
      store <- VfsDirStore.init(root, vfs)
      _     <- store.write(CacheKey("kymora/vfs/jvm/compile"), Chunk.from("x".getBytes), Maybe.empty)
      _     <- store.remove(CacheKey("kymora/vfs/jvm/compile"))
      r     <- store.read(CacheKey("kymora/vfs/jvm/compile"))
    yield assert(r.isEmpty)
  }
  "list returns known keys filtered by prefix" in {
    val root = VPath("cache")
    for
      vfs   <- Vfs.inMemory.init
      store <- VfsDirStore.init(root, vfs)
      _     <- store.write(CacheKey("kymora/vfs/jvm/compile"), Chunk.from("x".getBytes), Maybe.empty)
      _     <- store.write(CacheKey("kymora/vfs/js/compile"), Chunk.from("y".getBytes), Maybe.empty)
      _     <- store.write(CacheKey("other/thing"), Chunk.from("z".getBytes), Maybe.empty)
      hits  <- store.list("kymora/vfs/")
    yield
      assert(hits.size == 2)
      assert(hits.exists(_.value == "kymora/vfs/jvm/compile"))
      assert(hits.exists(_.value == "kymora/vfs/js/compile"))
  }
  "remove is idempotent on missing key" in {
    val root = VPath("cache")
    for
      vfs   <- Vfs.inMemory.init
      store <- VfsDirStore.init(root, vfs)
      _     <- store.remove(CacheKey("nothing"))
    yield assert(true)
  }
  "openWorkspace returns a fresh .dest.tmp path" in {
    for
      vfs   <- Vfs.inMemory.init
      root   = VPath("cache")
      store <- VfsDirStore.init(root, vfs)
      _ <- Scope.run {
        store.openWorkspace(CacheKey("compile")).map { dest =>
          assert(dest.show.endsWith(".dest.tmp"))
        }
      }
    yield ()
  }
  "openWorkspace dir is removed when Scope exits without seal" in {
    for
      vfs   <- Vfs.inMemory.init
      root   = VPath("cache")
      store <- VfsDirStore.init(root, vfs)
      destPath <- Scope.run {
        for
          dest <- store.openWorkspace(CacheKey("compile"))
          _    <- vfs.writeBytes(dest / "file.txt", Span.from("x".getBytes), createFolders = true)
        yield dest
      }
      stillThere <- vfs.exists(destPath)
    yield assert(!stillThere)
  }
  "openPersistentWorkspace returns the .dest path directly".timeout(3.minutes) in {
    for
      vfs   <- Vfs.inMemory.init
      root   = VPath("cache")
      store <- VfsDirStore.init(root, vfs)
      _ <- Scope.run {
        store.openPersistentWorkspace(CacheKey("persistent.foo")).map { dest =>
          assert(dest.show.endsWith(".dest"))
          assert(!dest.show.endsWith(".dest.tmp"))
        }
      }
    yield ()
  }
  "openPersistentWorkspace retains content across calls".timeout(3.minutes) in {
    for
      vfs   <- Vfs.inMemory.init
      root   = VPath("cache")
      store <- VfsDirStore.init(root, vfs)
      _ <- Scope.run {
        for
          dest <- store.openPersistentWorkspace(CacheKey("p"))
          _    <- vfs.writeBytes(dest / "marker", Span.from("kept".getBytes), createFolders = true)
        yield ()
      }
      _ <- Scope.run {
        for
          dest  <- store.openPersistentWorkspace(CacheKey("p"))
          bytes <- vfs.readBytes(dest / "marker")
        yield assert(bytes.toArray.sameElements("kept".getBytes))
      }
    yield ()
  }
  "openPersistentWorkspace serializes across stores with the same root".timeout(3.minutes) in {
    def run(store: CacheStore, state: AtomicRef[(Int, Int)]): Unit < (Async & Abort[StoreError]) =
      Scope.run {
        for
          _ <- store.openPersistentWorkspace(CacheKey("p"))
          _ <- state.updateAndGet { case (active, maxActive) =>
            val nextActive = active + 1
            (nextActive, math.max(maxActive, nextActive))
          }
          _ <- Async.sleep(100.millis)
          _ <- state.updateAndGet { case (active, maxActive) =>
            (active - 1, maxActive)
          }.unit
        yield ()
      }

    for
      vfs    <- Vfs.inMemory.init
      root    = VPath("cache")
      first  <- VfsDirStore.init(root, vfs)
      second <- VfsDirStore.init(root, vfs)
      state  <- AtomicRef.init((0, 0))
      a      <- Fiber.initUnscoped(run(first, state))
      _      <- Async.sleep(10.millis)
      b      <- Fiber.initUnscoped(run(second, state))
      _      <- a.get
      _      <- b.get
      (_, maxActive) <- state.get
    yield assert(maxActive == 1)
  }
end VfsDirStoreTests
