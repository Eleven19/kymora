package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class ConsoleReporterTests extends Test[Any]:
  "ConsoleReporter formats TaskCached event line" in {
    val line = ConsoleReporter.format(WorkflowEvent.TaskCached(TaskId("compile"), Fingerprint.unsafe("blake3:abc")))
    assert(line.contains("CACHED"))
    assert(line.contains("compile"))
  }
  "ConsoleReporter formats TaskCompleted event line" in {
    val line = ConsoleReporter.format(WorkflowEvent.TaskCompleted(TaskId("compile"), Fingerprint.unsafe("blake3:abc"), 100L))
    assert(line.contains("DONE"))
    assert(line.contains("compile"))
    assert(line.contains("100"))
  }
  "ConsoleReporter formats TaskFailed event line" in {
    val line = ConsoleReporter.format(WorkflowEvent.TaskFailed(TaskId("compile"), "boom"))
    assert(line.contains("FAILED"))
    assert(line.contains("compile"))
    assert(line.contains("boom"))
  }
end ConsoleReporterTests
