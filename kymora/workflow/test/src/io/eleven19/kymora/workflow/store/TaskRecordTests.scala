package io.eleven19.kymora.workflow.store

import io.eleven19.kymora.workflow.*
import kyo.*
import kyo.test.*

class TaskRecordTests extends Test[Any]:
  "TaskRecord round-trips through Json for Int value" in {
    val r = TaskRecord[Int](
      value = 42,
      valueHash = Fingerprint.unsafe("blake3:abc"),
      inputsHash = Fingerprint.unsafe("blake3:def"),
      bodyVersion = TaskVersion(1, 0, 0),
      bodyHash = Fingerprint.unsafe("blake3:ghi"),
      workflowVersion = "0.1.0",
      scalaVersion = "3.8.4",
      runtime = Runtime("darwin", "aarch64", Maybe("Temurin 25")),
      createdAt = java.time.Instant.parse("2026-06-16T18:42:11Z"),
    )
    val schema = summon[Schema[TaskRecord[Int]]]
    val s      = schema.encodeString[Json](r)
    val back   = schema.decodeString[Json](s)
    assert(back == Result.succeed(r))
  }
end TaskRecordTests
