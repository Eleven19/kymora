package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class ObserverTests extends Test[Any]:
  "Observer.NoOp swallows events without effect" in {
    for
      _ <- Observer.NoOp.onEvent(WorkflowEvent.TaskQueued(TaskId("foo")))
      _ <- Observer.NoOp.onEvent(WorkflowEvent.TaskFailed(TaskId("bar"), "x"))
    yield assert(true) // no-op; if it didn't throw, success
  }
  "Observer.NoOp.openSession returns a closeable Session" in {
    Scope.run {
      for
        session <- Observer.NoOp.openSession()
        _       <- session.onEvent(WorkflowEvent.TaskQueued(TaskId("foo")))
      yield assert(true)
    }
  }
end ObserverTests
