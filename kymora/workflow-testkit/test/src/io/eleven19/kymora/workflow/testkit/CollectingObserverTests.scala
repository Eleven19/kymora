package io.eleven19.kymora.workflow.testkit

import io.eleven19.kymora.workflow.*
import kyo.*
import kyo.test.*

import io.eleven19.kymora.workflow.testkit.CollectingObserver

class CollectingObserverTests extends Test[Any]:
  "CollectingObserver captures emitted events in order" in {
    for
      observer <- CollectingObserver.init
      _        <- observer.onEvent(WorkflowEvent.TaskQueued(TaskId("a")))
      _        <- observer.onEvent(WorkflowEvent.TaskQueued(TaskId("b")))
      events   <- observer.events
    yield
      assert(events.size == 2)
      assert(events(0) == WorkflowEvent.TaskQueued(TaskId("a")))
      assert(events(1) == WorkflowEvent.TaskQueued(TaskId("b")))
  }
  "CollectingObserver.queued lists only TaskQueued ids" in {
    for
      observer <- CollectingObserver.init
      _        <- observer.onEvent(WorkflowEvent.TaskQueued(TaskId("a")))
      _        <- observer.onEvent(WorkflowEvent.TaskQueued(TaskId("b")))
      queued   <- observer.queued
    yield
      assert(queued.size == 2)
      assert(queued.contains(TaskId("a")))
      assert(queued.contains(TaskId("b")))
  }
end CollectingObserverTests
