package io.eleven19.kymora.workflow.testkit

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.store.*
import kyo.*
import kyo.test.*

class WorkflowTestDriverTests extends Test[Any]:
  "WorkflowTestDriver.init returns a driver with empty event log" in {
    for
      driver <- WorkflowTestDriver.init
      events <- driver.events
    yield assert(events.isEmpty)
  }
  "WorkflowTestDriver.init wires up a working CacheStore" in {
    for
      driver <- WorkflowTestDriver.init
      _      <- driver.store.write(CacheKey("k"), Chunk.from("v".getBytes), Maybe.empty)
      r      <- driver.store.read(CacheKey("k"))
    yield
      assert(r.isDefined)
      assert(r.get.bytes == Chunk.from("v".getBytes))
  }
  "WorkflowTestDriver.events captures events emitted via the observer" in {
    for
      driver <- WorkflowTestDriver.init
      _      <- driver.observer.onEvent(WorkflowEvent.TaskQueued(TaskId("foo")))
      events <- driver.events
    yield
      assert(events.size == 1)
      assert(events.head == WorkflowEvent.TaskQueued(TaskId("foo")))
  }
end WorkflowTestDriverTests
