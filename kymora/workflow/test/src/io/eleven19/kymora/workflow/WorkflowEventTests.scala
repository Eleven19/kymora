package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class WorkflowEventTests extends Test[Any]:
  "TaskQueued equals itself" in {
    val a = WorkflowEvent.TaskQueued(TaskId("foo"))
    val b = WorkflowEvent.TaskQueued(TaskId("foo"))
    assert(a == b)
  }
  "RunStarted captures goals and timestamp" in {
    val now = java.time.Instant.parse("2026-06-17T00:00:00Z")
    val e   = WorkflowEvent.RunStarted(Chunk(TaskId("a"), TaskId("b")), now)
    assert(e.goals.size == 2)
    assert(e.at.equals(now))
  }
  "RunCompleted counts hits/misses/failed" in {
    val e = WorkflowEvent.RunCompleted(durationMs = 100L, hits = 3, misses = 2, failed = 1)
    assert(e.hits == 3)
    assert(e.misses == 2)
    assert(e.failed == 1)
  }
  "TaskCached carries the inputsHash" in {
    val fp = Fingerprint.unsafe("blake3:abc")
    val e  = WorkflowEvent.TaskCached(TaskId("compile"), fp)
    assert(e.inputsHash == fp)
  }
  "TaskCompleted carries valueHash and duration" in {
    val fp = Fingerprint.unsafe("blake3:def")
    val e  = WorkflowEvent.TaskCompleted(TaskId("compile"), fp, durationMs = 500L)
    assert(e.valueHash == fp)
    assert(e.durationMs == 500L)
  }
  "TaskFailed carries the error message" in {
    val e = WorkflowEvent.TaskFailed(TaskId("compile"), "boom")
    assert(e.message == "boom")
  }
end WorkflowEventTests
