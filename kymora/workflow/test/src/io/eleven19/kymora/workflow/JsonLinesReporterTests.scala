package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class JsonLinesReporterTests extends Test[Any]:
  "JsonLinesReporter encodes TaskCached as JSON line" in {
    val line = JsonLinesReporter.toJson(WorkflowEvent.TaskCached(TaskId("compile"), Fingerprint.unsafe("blake3:abc")))
    assert(line.contains("\"type\":\"TaskCached\""))
    assert(line.contains("\"id\":\"compile\""))
    assert(line.contains("\"inputsHash\":\"blake3:abc\""))
  }
  "JsonLinesReporter encodes TaskFailed as JSON line" in {
    val line = JsonLinesReporter.toJson(WorkflowEvent.TaskFailed(TaskId("compile"), "boom"))
    assert(line.contains("\"type\":\"TaskFailed\""))
    assert(line.contains("\"message\":\"boom\""))
  }
  "JsonLinesReporter encodes RunCompleted with all counts" in {
    val line = JsonLinesReporter.toJson(WorkflowEvent.RunCompleted(100L, 3, 2, 1))
    assert(line.contains("\"type\":\"RunCompleted\""))
    assert(line.contains("\"hits\":3"))
    assert(line.contains("\"misses\":2"))
    assert(line.contains("\"failed\":1"))
    assert(line.contains("\"durationMs\":100"))
  }
end JsonLinesReporterTests
