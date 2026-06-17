package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.store.*
import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

class CacheControlsTests extends Test[Any]:
  "Workflow.purge wipes all keys" in {
    for
      driver <- WorkflowTestDriver.init
      _      <- driver.store.write(CacheKey("a"), Chunk.from("x".getBytes), Maybe.empty)
      _      <- driver.store.write(CacheKey("b"), Chunk.from("y".getBytes), Maybe.empty)
      _      <- Env.run(driver.config)(Workflow.purge())
      a      <- driver.store.read(CacheKey("a"))
      b      <- driver.store.read(CacheKey("b"))
    yield
      assert(a.isEmpty)
      assert(b.isEmpty)
  }
  "Workflow.clean(prefix) only removes matching keys" in {
    for
      driver <- WorkflowTestDriver.init
      _      <- driver.store.write(CacheKey("kymora/vfs/jvm/compile"), Chunk.from("x".getBytes), Maybe.empty)
      _      <- driver.store.write(CacheKey("kymora/core/jvm/compile"), Chunk.from("y".getBytes), Maybe.empty)
      _      <- driver.store.write(CacheKey("other/thing"), Chunk.from("z".getBytes), Maybe.empty)
      _      <- Env.run(driver.config)(Workflow.clean("kymora/"))
      a      <- driver.store.read(CacheKey("kymora/vfs/jvm/compile"))
      b      <- driver.store.read(CacheKey("kymora/core/jvm/compile"))
      c      <- driver.store.read(CacheKey("other/thing"))
    yield
      assert(a.isEmpty)
      assert(b.isEmpty)
      assert(c.isDefined)
  }
  "Config.verifyDest is observable via Env" in {
    for
      driver <- WorkflowTestDriver.init
      cfg     = driver.config.copy(verifyDest = true)
      v      <- Env.run(cfg)(Env.use[Workflow.Config](_.verifyDest))
    yield assert(v)
  }
end CacheControlsTests
