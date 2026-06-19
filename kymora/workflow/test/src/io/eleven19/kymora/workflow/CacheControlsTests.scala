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
      _      <- Workflow.handle(driver.runtime)(Workflow.purge())
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
      _      <- Workflow.handle(driver.runtime)(Workflow.clean("kymora/"))
      a      <- driver.store.read(CacheKey("kymora/vfs/jvm/compile"))
      b      <- driver.store.read(CacheKey("kymora/core/jvm/compile"))
      c      <- driver.store.read(CacheKey("other/thing"))
    yield
      assert(a.isEmpty)
      assert(b.isEmpty)
      assert(c.isDefined)
  }
  "Config.verifyDest is observable via Runtime" in {
    for
      driver <- WorkflowTestDriver.init
      cfg     = driver.config.copy(verifyDest = true)
      runtime = driver.runtime.copy(config = cfg)
    yield assert(runtime.config.verifyDest)
  }
  "Config.bypass forces a cached task body to run even when a record exists" in {
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    val goal = Task.cached("bypassed") {
      count.incrementAndGet()
    }

    for
      driver <- WorkflowTestDriver.init
      first  <- driver.run(goal)
      runtime = driver.runtime.copy(config = driver.config.copy(bypass = Set(goal.id)))
      second <- Workflow.handle(runtime)(Workflow.run(goal))
      events <- driver.events
    yield
      assert(first == 1)
      assert(second == 2)
      assert(count.get() == 2)
      assert(events.collect { case e: WorkflowEvent.TaskCached if e.id == goal.id => e }.isEmpty)
  }
  "Config.noCache neither reads nor writes cache records" in {
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    val goal = Task.cached("uncached") {
      count.incrementAndGet()
    }

    for
      driver <- WorkflowTestDriver.init
      runtime = driver.runtime.copy(config = driver.config.copy(noCache = true))
      first  <- Workflow.handle(runtime)(Workflow.run(goal))
      second <- Workflow.handle(runtime)(Workflow.run(goal))
      stored <- driver.store.read(CacheKey("uncached"))
      events <- driver.events
    yield
      assert(first == 1)
      assert(second == 2)
      assert(count.get() == 2)
      assert(stored.isEmpty)
      assert(events.collect { case e: WorkflowEvent.TaskCached if e.id == goal.id => e }.isEmpty)
  }
  "Config.readOnly can read existing records but does not write misses" in {
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    val goal = Task.cached("readonly") {
      count.incrementAndGet()
    }

    for
      driver <- WorkflowTestDriver.init
      runtime = driver.runtime.copy(config = driver.config.copy(readOnly = true))
      first  <- Workflow.handle(runtime)(Workflow.run(goal))
      missing <- driver.store.read(CacheKey("readonly"))
      _      <- driver.run(goal)
      cached <- Workflow.handle(runtime)(Workflow.run(goal))
    yield
      assert(first == 1)
      assert(missing.isEmpty)
      assert(cached == 2)
      assert(count.get() == 2)
  }
end CacheControlsTests
