package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.testkit.*
import io.eleven19.kymora.vfs.*
import kyo.*
import kyo.test.*

class PersistentEngineTests extends Test[Any]:
  "Task.persistent leaf executes its body and returns the value" in {
    val goal = Task.persistent("p")(42)
    for
      driver <- WorkflowTestDriver.init
      result <- driver.run(goal)
    yield assert(result == 42)
  }
  "Task.persistent (1 dep) chains through scheduler" in {
    val dep  = Task.init("dep")(10)
    val goal = Task.persistent("p")(dep) { x => x + 1 }
    for
      driver <- WorkflowTestDriver.init
      result <- driver.run(goal)
    yield assert(result == 11)
  }
  "Task.persistent reuses the real .dest contents across body invocations" in {
    val goal = Task.persistent("retained") {
      for
        dest <- Workflow.dest
        marker = dest / "marker.txt"
        vfs    <- Vfs.get
        exists <- vfs.exists(marker)
        value  <- if exists then marker.read else marker.write("first").map(_ => "first")
      yield s"${dest.show}:$value"
    }
    val expectedDest = VPath("cache") / "retained.dest"

    for
      driver <- WorkflowTestDriver.init
      runtime = driver.runtime.copy(config = driver.config.copy(noCache = true))
      first  <- Workflow.handle(runtime)(Workflow.run(goal))
      _      <- driver.vfs.write(expectedDest / "marker.txt", "second")
      second <- Workflow.handle(runtime)(Workflow.run(goal))
    yield
      assert(first == s"${expectedDest.show}:first")
      assert(second == s"${expectedDest.show}:second")
  }
  "Task.persistent concurrent runs for the same key serialize body execution".timeout(3.minutes) in {
    for
      state <- AtomicRef.init((0, 0, 0))
      goal = Task.persistent("serialized") {
        for
          snapshot <- state.updateAndGet { case (active, maxActive, attempts) =>
            val nextActive = active + 1
            (nextActive, math.max(maxActive, nextActive), attempts + 1)
          }
          _ <- Async.sleep(100.millis)
          _ <- state.updateAndGet { case (active, maxActive, attempts) =>
            (active - 1, maxActive, attempts)
          }.unit
        yield snapshot._3
      }
      driver <- WorkflowTestDriver.init
      runtime = driver.runtime.copy(config = driver.config.copy(noCache = true))
      first <- Fiber.initUnscoped(Workflow.handle(runtime)(Workflow.run(goal)))
      _ <- Async.sleep(10.millis)
      second <- Fiber.initUnscoped(Workflow.handle(runtime)(Workflow.run(goal)))
      firstValue  <- first.get
      secondValue <- second.get
      (_, maxActive, attempts) <- state.get
    yield
      assert(attempts == 2)
      assert(maxActive == 1)
      assert(Set(firstValue, secondValue) == Set(1, 2))
  }
  "Task.persistent failure leaves partial .dest contents intact".timeout(3.minutes) in {
    val goal = Task.persistent("fails") {
      for
        dest <- Workflow.dest
        file = dest / "partial.txt"
        _ <- file.write("partial")
        _ <- Sync.defer(throw RuntimeException("boom"))
      yield 0
    }
    val partial = VPath("cache") / "fails.dest" / "partial.txt"

    for
      driver  <- WorkflowTestDriver.init
      attempt <- Abort.run[WorkflowError](driver.run(goal))
      exists  <- driver.vfs.exists(partial)
      text    <- driver.vfs.read(partial)
    yield
      assert(attempt.isFailure)
      assert(exists)
      assert(text == "partial")
  }
end PersistentEngineTests
