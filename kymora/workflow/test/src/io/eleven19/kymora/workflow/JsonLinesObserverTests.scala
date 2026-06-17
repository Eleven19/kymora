package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class JsonLinesObserverTests extends Test[Any]:
  "JsonLinesObserver encodes TaskCached as JSON line" in {
    val line = JsonLinesObserver.toJson(WorkflowEvent.TaskCached(TaskId("compile"), Fingerprint.unsafe("blake3:abc")))
    assert(line.contains("\"type\":\"TaskCached\""))
    assert(line.contains("\"id\":\"compile\""))
    assert(line.contains("\"inputsHash\":\"blake3:abc\""))
  }
  "JsonLinesObserver encodes TaskFailed as JSON line" in {
    val line = JsonLinesObserver.toJson(WorkflowEvent.TaskFailed(TaskId("compile"), "boom"))
    assert(line.contains("\"type\":\"TaskFailed\""))
    assert(line.contains("\"message\":\"boom\""))
  }
  "JsonLinesObserver encodes RunCompleted with all counts" in {
    val line = JsonLinesObserver.toJson(WorkflowEvent.RunCompleted(100L, 3, 2, 1))
    assert(line.contains("\"type\":\"RunCompleted\""))
    assert(line.contains("\"hits\":3"))
    assert(line.contains("\"misses\":2"))
    assert(line.contains("\"failed\":1"))
    assert(line.contains("\"durationMs\":100"))
  }
end JsonLinesObserverTests
