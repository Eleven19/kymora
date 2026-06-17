package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class ErrorsTests extends Test[Any]:
  "DuplicateTaskId equals itself" in {
    val a = WorkflowError.DuplicateTaskId(TaskId("x"), Chunk("Task.Cached"))
    val b = WorkflowError.DuplicateTaskId(TaskId("x"), Chunk("Task.Cached"))
    assert(a == b)
  }
  "WorkflowError CycleDetected preserves cycle order" in {
    val e: WorkflowError =
      WorkflowError.CycleDetected(Chunk(TaskId("a"), TaskId("b")))
    e match
      case WorkflowError.CycleDetected(cycle) =>
        assert(cycle.size == 2)
        assert(cycle.head == TaskId("a"))
        assert(cycle.last == TaskId("b"))
      case _ => fail("expected CycleDetected")
  }
  "WorkflowError.Store wraps a StoreError that Schema-round-trips" in {
    val cause: StoreError = StoreError.CorruptManifest("/cache/x", "bad header")
    val wrapped           = WorkflowError.Store(cause)
    // Storage errors are Schema-derivable (no TaskId in scope), so we exercise
    // the round-trip on `StoreError` directly — this is the wire form the
    // engine will persist when a Store failure needs to cross a boundary.
    val s = Schema[StoreError].encodeString[Json](cause)
    val r = Schema[StoreError].decodeString[Json](s)
    assert(r == Result.succeed(cause))
    assert(wrapped == WorkflowError.Store(cause))
  }
  "CliParseError.UnknownCommand has available list" in {
    val e = CliParseError.UnknownCommand("foo", Chunk(TaskId("a"), TaskId("b")))
    assert(e.available.size == 2)
  }
end ErrorsTests
