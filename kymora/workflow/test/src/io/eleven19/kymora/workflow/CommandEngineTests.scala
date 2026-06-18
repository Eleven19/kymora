package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

class CommandEngineTests extends Test[Any]:
  "Task.command leaf runs its body and returns the value" in {
    val goal = Task.command("run")("hello")
    for
      driver <- WorkflowTestDriver.init
      result <- driver.run(goal)
    yield assert(result == "hello")
  }
  "Command body runs on every invocation (no caching)" in {
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    val goal  = Task.command("run") { val _ = count.incrementAndGet(); 42 }
    for
      driver <- WorkflowTestDriver.init
      _      <- driver.run(goal)
      _      <- driver.run(goal)
      _      <- driver.run(goal)
    yield assert(count.get() == 3)
  }
  "Command depends on Task.Cached; dep memoizes within one run, command always runs" in {
    val depCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val cmdCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val dep      = Task.init("dep") { val _ = depCount.incrementAndGet(); 10 }
    val goal     = Task.command("run")(dep) { x =>
      val _ = cmdCount.incrementAndGet(); x + 1
    }
    for
      driver <- WorkflowTestDriver.init
      _      <- driver.run(goal)
      _      <- driver.run(goal)
    yield
      assert(cmdCount.get() == 2)
      assert(depCount.get() == 1)
  }
end CommandEngineTests
