package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class ReporterTests extends Test[Any]:
  "Reporter.NoOp swallows events without effect" in {
    for
      _ <- Reporter.NoOp.onEvent(WorkflowEvent.TaskQueued(TaskId("foo")))
      _ <- Reporter.NoOp.onEvent(WorkflowEvent.TaskFailed(TaskId("bar"), "x"))
    yield assert(true) // no-op; if it didn't throw, success
  }
  "Reporter.NoOp.openSession returns a closeable Session" in {
    Scope.run {
      for
        session <- Reporter.NoOp.openSession()
        _       <- session.onEvent(WorkflowEvent.TaskQueued(TaskId("foo")))
      yield assert(true)
    }
  }
end ReporterTests
