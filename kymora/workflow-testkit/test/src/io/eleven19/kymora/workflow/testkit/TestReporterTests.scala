package io.eleven19.kymora.workflow.testkit

import io.eleven19.kymora.workflow.*
import kyo.*
import kyo.test.*

import io.eleven19.kymora.workflow.testkit.TestReporter

class TestReporterTests extends Test[Any]:
  "TestReporter captures emitted events in order" in {
    for
      reporter <- TestReporter.init
      _        <- reporter.onEvent(WorkflowEvent.TaskQueued(TaskId("a")))
      _        <- reporter.onEvent(WorkflowEvent.TaskQueued(TaskId("b")))
      events   <- reporter.events
    yield
      assert(events.size == 2)
      assert(events(0) == WorkflowEvent.TaskQueued(TaskId("a")))
      assert(events(1) == WorkflowEvent.TaskQueued(TaskId("b")))
  }
  "TestReporter.queued lists only TaskQueued ids" in {
    for
      reporter <- TestReporter.init
      _        <- reporter.onEvent(WorkflowEvent.TaskQueued(TaskId("a")))
      _        <- reporter.onEvent(WorkflowEvent.TaskQueued(TaskId("b")))
      queued   <- reporter.queued
    yield
      assert(queued.size == 2)
      assert(queued.contains(TaskId("a")))
      assert(queued.contains(TaskId("b")))
  }
end TestReporterTests
