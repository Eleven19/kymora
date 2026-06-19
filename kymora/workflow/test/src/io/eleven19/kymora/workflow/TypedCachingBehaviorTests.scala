package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.store.CacheKey
import io.eleven19.kymora.workflow.store.TaskRecord
import io.eleven19.kymora.workflow.internal.Hashing
import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

final case class CachedReport(name: String, total: Int) derives Schema, CanEqual
final case class ExplicitCacheableReport(name: String) derives Schema, Cacheable, CanEqual
final case class HashSeed(stable: Int, volatile: Int) derives Schema, CanEqual

class TypedCachingBehaviorTests extends Test[Any]:

  "Cached value evaluates once and later invocations decode the stored value" in {
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    val goal = Task.cached("report") {
      CachedReport("run", count.incrementAndGet())
    }

    for
      driver <- WorkflowTestDriver.init
      first  <- driver.run(goal)
      second <- driver.run(goal)
      events <- driver.events
    yield
      assert(first == CachedReport("run", 1))
      assert(second == CachedReport("run", 1))
      assert(count.get() == 1)
      assert(events.collect { case e: WorkflowEvent.TaskCached => e }.size == 1)
  }

  "Cached dependency shared by multiple dependents is evaluated or decoded once per execution" in {
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    val base = Task.cached("base") {
      CachedReport("base", count.incrementAndGet())
    }
    val left  = Task.cached("left")(base)(report => report.total + 10)
    val right = Task.cached("right")(base)(report => report.total + 20)

    for
      driver <- WorkflowTestDriver.init
      first  <- Workflow.handle(driver.runtime)(Workflow.runAll(left, right))
      second <- Workflow.handle(driver.runtime)(Workflow.runAll(left, right))
    yield
      assert(first == Chunk(11, 21))
      assert(second == Chunk(11, 21))
      assert(count.get() == 1)
  }

  "Json cache manifest stores a readable value field" in {
    val goal = Task.cached("manifest") {
      CachedReport("readable", 7)
    }

    for
      driver   <- WorkflowTestDriver.init
      _        <- driver.run(goal)
      manifest <- driver.store.read(CacheKey("manifest"))
      text      = manifest.map(stored => String(stored.bytes.toArray))
    yield
      assert(text.exists(_.contains("\"value\"")))
      assert(text.exists(_.contains("readable")))
      assert(text.exists(_.contains("\"schemaVersion\"")))
  }

  "Task outputs can explicitly derive Cacheable from Schema" in {
    val goal = Task.cached("explicit-cacheable") {
      ExplicitCacheableReport("ok")
    }

    for
      driver <- WorkflowTestDriver.init
      value  <- driver.run(goal)
    yield assert(value == ExplicitCacheableReport("ok"))
  }

  "Protobuf runtime codec round-trips cached values" in {
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    val goal = Task.cached("protobuf-report") {
      CachedReport("protobuf", count.incrementAndGet())
    }

    for
      driver <- WorkflowTestDriver.init
      runtime = driver.runtime.copy(codec = Protobuf())
      first  <- Workflow.handle(runtime)(goal())
      second <- Workflow.handle(runtime)(goal())
    yield
      assert(first == CachedReport("protobuf", 1))
      assert(second == CachedReport("protobuf", 1))
      assert(count.get() == 1)
  }

  "Corrupt cache manifest fails as WorkflowError.Store" in {
    val goal = Task.cached("corrupt") {
      CachedReport("fresh", 1)
    }

    for
      driver  <- WorkflowTestDriver.init
      _       <- driver.store.write(CacheKey("corrupt"), Chunk.from("not json".getBytes), Maybe.empty)
      attempt <- Abort.run[WorkflowError](driver.run(goal))
    yield
      assert(attempt.isFailure)
      assert(attempt.fold(_ => false, _.isInstanceOf[WorkflowError.Store], _ => false))
  }

  "Cached value-hash mismatch fails as WorkflowError.Store" in {
    val goal = Task.cached("mismatch") {
      CachedReport("fresh", 1)
    }
    val bodyHash   = Hashing.bodyHash(goal.id, goal.version)
    val inputsHash = Hashing.inputsHash(goal.id, bodyHash, Chunk.empty)
    val record = TaskRecord[CachedReport](
      schemaVersion = TaskRecord.CurrentSchemaVersion,
      value = CachedReport("stored", 9),
      valueHash = Fingerprint.unsafe("blake3:wrong"),
      inputsHash = inputsHash,
      bodyVersion = goal.version,
      bodyHash = bodyHash,
      workflowVersion = "test",
      scalaVersion = "3.8.4",
      runtime = store.Runtime("test", "test", Maybe.empty),
      createdAt = java.time.Instant.parse("2026-06-16T18:42:11Z"),
    )
    given Codec = Json()
    val bytes = Chunk.from(summon[Schema[TaskRecord[CachedReport]]].encode(record).toArray)

    for
      driver  <- WorkflowTestDriver.init
      _       <- driver.store.write(CacheKey("mismatch"), bytes, Maybe.empty)
      attempt <- Abort.run[WorkflowError](driver.run(goal))
    yield
      assert(attempt.isFailure)
      assert(attempt.fold(_ => false, _.isInstanceOf[WorkflowError.Store], _ => false))
  }

  "Stale cache manifest with mismatched inputs hash is treated as a miss" in {
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    val goal = Task.cached("stale") {
      CachedReport("fresh", count.incrementAndGet())
    }
    val bodyHash = Hashing.bodyHash(goal.id, goal.version)
    val record = TaskRecord[CachedReport](
      schemaVersion = TaskRecord.CurrentSchemaVersion,
      value = CachedReport("stored", 9),
      valueHash = summon[Hashable[CachedReport]].hash(CachedReport("stored", 9)),
      inputsHash = Fingerprint.unsafe("blake3:stale"),
      bodyVersion = goal.version,
      bodyHash = bodyHash,
      workflowVersion = "test",
      scalaVersion = "3.8.4",
      runtime = store.Runtime("test", "test", Maybe.empty),
      createdAt = java.time.Instant.parse("2026-06-16T18:42:11Z"),
    )
    given Codec = Json()
    val bytes = Chunk.from(summon[Schema[TaskRecord[CachedReport]]].encode(record).toArray)

    for
      driver <- WorkflowTestDriver.init
      _      <- driver.store.write(CacheKey("stale"), bytes, Maybe.empty)
      value  <- driver.run(goal)
      events <- driver.events
    yield
      assert(value == CachedReport("fresh", 1))
      assert(count.get() == 1)
      assert(events.collect { case e: WorkflowEvent.TaskCached if e.id == goal.id => e }.isEmpty)
      assert(events.collect { case e: WorkflowEvent.TaskCompleted if e.id == goal.id => e }.size == 1)
  }

  "Custom Hashable customizes downstream invalidation" in {
    given Hashable[HashSeed] =
      seed => summon[Hashable[Int]].hash(seed.stable)

    val activityCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val derivedCount  = new java.util.concurrent.atomic.AtomicInteger(0)
    val seed = Task.activity("hash-seed") {
      HashSeed(1, activityCount.incrementAndGet())
    }
    val derived = Task.cached("hash-derived")(seed) { s =>
      val _ = derivedCount.incrementAndGet()
      s.volatile
    }

    for
      driver <- WorkflowTestDriver.init
      first  <- driver.run(derived)
      second <- driver.run(derived)
    yield
      assert(first == 1)
      assert(second == 1)
      assert(activityCount.get() == 2)
      assert(derivedCount.get() == 1)
  }
end TypedCachingBehaviorTests
