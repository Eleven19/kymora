package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

class EngineTests extends Test[Any]:
  "Leaf Task.Cached executes its body and returns the value" in {
    val goal = Task.init("foo")(42)
    for
      driver <- WorkflowTestDriver.init
      result <- driver.run(goal)
    yield assert(result == 42)
  }
  "Chained Task.Cached propagates values from deps" in {
    val a = Task.init("a")(10)
    val b = Task.init("b")(a) { x => x + 1 }
    val c = Task.init("c")(b) { x => x * 2 }
    for
      driver <- WorkflowTestDriver.init
      result <- driver.run(c)
    yield assert(result == 22)
  }
  "Second invocation hits the cache (emits TaskCached)" in {
    val goal = Task.init("foo")(42)
    for
      driver <- WorkflowTestDriver.init
      _      <- driver.run(goal)
      _      <- driver.run(goal)
      events <- driver.events
    yield
      assert(events.collect { case e: WorkflowEvent.TaskCached => e }.size == 1)
      assert(events.collect { case e: WorkflowEvent.TaskCompleted => e }.size == 1)
  }

  "Runtime observer still receives scheduler events through default telemetry" in {
    val goal = Task.init("foo")(42)

    for
      vfs      <- io.eleven19.kymora.vfs.Vfs.inMemory.init
      observer <- CollectingObserver.init
      runtime = Workflow.Runtime(vfs = vfs, observer = observer)
      result <- Workflow.handle(runtime)(Workflow.run(goal))
      events <- observer.events
    yield
      assert(result == 42)
      assert(events.exists {
        case WorkflowEvent.TaskCompleted(id, _, _) if id == TaskId("foo") => true
        case _                                                            => false
      })
  }
end EngineTests
