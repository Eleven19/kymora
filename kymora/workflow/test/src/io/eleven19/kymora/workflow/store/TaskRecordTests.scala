package io.eleven19.kymora.workflow.store

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.vfs.{Vfs, VPath}
import kyo.*
import kyo.test.*

final case class StoredReport(name: String, total: Int) derives Schema, CanEqual

class TaskRecordTests extends Test[Any]:
  "TaskRecord round-trips through Json for Int value" in {
    val r = TaskRecord[Int](
      schemaVersion = TaskRecord.CurrentSchemaVersion,
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
  "TaskRecord round-trips through VfsDirStore for Int value" in {
    val r = TaskRecord[Int](
      schemaVersion = TaskRecord.CurrentSchemaVersion,
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
    given Codec = Json()
    val bytes = Chunk.from(summon[Schema[TaskRecord[Int]]].encode(r).toArray)

    for
      vfs    <- Vfs.inMemory.init
      store  <- VfsDirStore.init(VPath("cache"), vfs)
      _      <- store.write(CacheKey("record/int"), bytes, Maybe.empty)
      stored <- store.read(CacheKey("record/int"))
      decoded = stored.map(s => summon[Schema[TaskRecord[Int]]].decode(Span.fromUnsafe(s.bytes.toArray)))
    yield assert(decoded.contains(Result.succeed(r)))
  }
  "TaskRecord round-trips through VfsDirStore for structured value" in {
    val r = TaskRecord[StoredReport](
      schemaVersion = TaskRecord.CurrentSchemaVersion,
      value = StoredReport("docs", 7),
      valueHash = Fingerprint.unsafe("blake3:abc"),
      inputsHash = Fingerprint.unsafe("blake3:def"),
      bodyVersion = TaskVersion(1, 0, 0),
      bodyHash = Fingerprint.unsafe("blake3:ghi"),
      workflowVersion = "0.1.0",
      scalaVersion = "3.8.4",
      runtime = Runtime("darwin", "aarch64", Maybe("Temurin 25")),
      createdAt = java.time.Instant.parse("2026-06-16T18:42:11Z"),
    )
    given Codec = Json()
    val bytes = Chunk.from(summon[Schema[TaskRecord[StoredReport]]].encode(r).toArray)

    for
      vfs    <- Vfs.inMemory.init
      store  <- VfsDirStore.init(VPath("cache"), vfs)
      _      <- store.write(CacheKey("record/report"), bytes, Maybe.empty)
      stored <- store.read(CacheKey("record/report"))
      decoded = stored.map(s => summon[Schema[TaskRecord[StoredReport]]].decode(Span.fromUnsafe(s.bytes.toArray)))
    yield assert(decoded.contains(Result.succeed(r)))
  }
end TaskRecordTests
