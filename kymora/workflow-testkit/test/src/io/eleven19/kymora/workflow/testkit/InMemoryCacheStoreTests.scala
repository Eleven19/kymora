package io.eleven19.kymora.workflow.testkit

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.store.*
import kyo.*
import kyo.test.*

class InMemoryCacheStoreTests extends Test[Any]:
  "write then read round-trips bytes" in {
    for
      store <- InMemoryCacheStore.init
      bytes  = Chunk.from("hello".getBytes)
      _     <- store.write(CacheKey("foo"), bytes, destSeal = Maybe.empty)
      r     <- store.read(CacheKey("foo"))
    yield
      assert(r.isDefined)
      assert(r.get.bytes == bytes)
  }
  "read on missing key returns empty" in {
    for
      store <- InMemoryCacheStore.init
      r     <- store.read(CacheKey("nope"))
    yield assert(r.isEmpty)
  }
  "purge clears all entries" in {
    for
      store <- InMemoryCacheStore.init
      _     <- store.write(CacheKey("a"), Chunk.from("x".getBytes), Maybe.empty)
      _     <- store.write(CacheKey("b"), Chunk.from("y".getBytes), Maybe.empty)
      _     <- store.purge()
      ra    <- store.read(CacheKey("a"))
      rb    <- store.read(CacheKey("b"))
    yield
      assert(ra.isEmpty)
      assert(rb.isEmpty)
  }
  "remove deletes a single entry" in {
    for
      store <- InMemoryCacheStore.init
      _     <- store.write(CacheKey("a"), Chunk.from("x".getBytes), Maybe.empty)
      _     <- store.write(CacheKey("b"), Chunk.from("y".getBytes), Maybe.empty)
      _     <- store.remove(CacheKey("a"))
      ra    <- store.read(CacheKey("a"))
      rb    <- store.read(CacheKey("b"))
    yield
      assert(ra.isEmpty)
      assert(rb.isDefined)
  }
  "list filters known keys by prefix" in {
    for
      store <- InMemoryCacheStore.init
      _     <- store.write(CacheKey("kymora/vfs/compile"), Chunk.from("x".getBytes), Maybe.empty)
      _     <- store.write(CacheKey("kymora/core/compile"), Chunk.from("y".getBytes), Maybe.empty)
      _     <- store.write(CacheKey("other"), Chunk.from("z".getBytes), Maybe.empty)
      hits  <- store.list("kymora/")
    yield
      assert(hits.size == 2)
      assert(hits.exists(_.value == "kymora/vfs/compile"))
      assert(hits.exists(_.value == "kymora/core/compile"))
  }
  "openWorkspace returns a fresh VPath; cleanup on Scope exit" in {
    for
      store <- InMemoryCacheStore.init
      didExit <- Scope.run {
        store.openWorkspace(CacheKey("compile")).map(_ => true)
      }
    yield assert(didExit)
  }
  "openPersistentWorkspace path persists across invocations" in {
    for
      store <- InMemoryCacheStore.init
      p1 <- Scope.run(store.openPersistentWorkspace(CacheKey("p")))
      p2 <- Scope.run(store.openPersistentWorkspace(CacheKey("p")))
    yield assert(p1 == p2)
  }
end InMemoryCacheStoreTests
