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

  "WorkflowTestDriver exposes live telemetry snapshots while preserving collected events" in {
    val goal = Task.init("foo")(42)

    for
      driver   <- WorkflowTestDriver.init
      result   <- driver.run(goal)
      snapshot <- driver.telemetry.snapshot
      events   <- driver.events
    yield
      assert(result == 42)
      assert(events.nonEmpty)
      assert(snapshot.events == events)
      assert(snapshot.tasks(TaskId("foo")).status == WorkflowRunState.TaskStatus.Succeeded)
  }

  "WorkflowTestDriver keeps telemetry and observer event order aligned under parallel execution" in {
    val goals = (0 until 16).map(i => Task.init(s"parallel-$i")(i))

    for
      driver <- WorkflowTestDriver.init
      runtime = driver.runtime.copy(config = driver.config.copy(parallelism = 4))
      _        <- Workflow.handle(runtime)(Workflow.runAll(goals*))
      snapshot <- driver.telemetry.snapshot
      events   <- driver.events
    yield
      assert(events.nonEmpty)
      assert(snapshot.events == events)
  }

  "WorkflowTestDriver telemetry supports subscriptions" in {
    val event = WorkflowEvent.TaskQueued(TaskId("driver-subscription"))

    Scope.run {
      for
        driver       <- WorkflowTestDriver.init
        subscription <- driver.telemetry.subscribe(bufferSize = 8)
        _            <- driver.telemetry.publish(event)
        seen         <- subscription.take
        events       <- driver.events
      yield
        assert(seen == event)
        assert(events == Chunk(event))
    }
  }
end WorkflowTestDriverTests
