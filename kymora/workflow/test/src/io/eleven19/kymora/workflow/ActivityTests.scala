package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.store.CacheKey
import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

class ActivityTests extends Test[Any]:

  "Activity evaluates once per workflow execution and does not write a cache record" in {
    val count    = new java.util.concurrent.atomic.AtomicInteger(0)
    val activity = Task.activity("clock")(count.incrementAndGet())
    val left     = Task.cached("left")(activity)(_ + 1)
    val right    = Task.cached("right")(activity)(_ + 2)

    for
      driver <- WorkflowTestDriver.init
      first  <- Workflow.handle(driver.runtime)(Workflow.runAll(left, right))
      cached <- driver.store.read(CacheKey("clock"))
      second <- Workflow.handle(driver.runtime)(Workflow.runAll(left, right))
    yield
      assert(first == Chunk(2, 3))
      assert(second == Chunk(3, 4))
      assert(count.get() == 2)
      assert(cached.isEmpty)
  }

  "Cached dependents invalidate when an Activity output hash changes" in {
    val count    = new java.util.concurrent.atomic.AtomicInteger(0)
    val activity = Task.activity("seed")(count.incrementAndGet())
    val derivedCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val derived = Task.cached("derived")(activity) { seed =>
      val _ = derivedCount.incrementAndGet()
      seed * 10
    }

    for
      driver <- WorkflowTestDriver.init
      first  <- driver.run(derived)
      second <- driver.run(derived)
    yield
      assert(first == 10)
      assert(second == 20)
      assert(count.get() == 2)
      assert(derivedCount.get() == 2)
  }
end ActivityTests
