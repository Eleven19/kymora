package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.internal.Hashing
import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

/** Coverage for `Task.cached[A, P]`, `Task.persistent[A, P]`, and
  * `Task.command[A, P]` — the parameterized variants where the runtime
  * parameter `P` joins the cache key for Cached/Persistent and closes over
  * the body for Command.
  *
  * Cache-key behaviour is verified directly via [[Hashing.inputsHash]] on the
  * `paramHash` field that the constructors thread through; end-to-end
  * command behaviour is verified through [[WorkflowTestDriver]].
  */
class ParameterizedTaskTests extends Test[Any]:
  "Task.cached[A, P] same id + same P produces same inputsHash" in {
    val factory     = Task.cached[String, Int]("compute") { p => s"out:$p" }
    val a           = factory(7).asInstanceOf[Task.Cached[String]]
    val b           = factory(7).asInstanceOf[Task.Cached[String]]
    val bodyHashA   = Hashing.bodyHash(a.id, a.version)
    val bodyHashB   = Hashing.bodyHash(b.id, b.version)
    val inputsHashA = Hashing.inputsHash(a.id, bodyHashA, Chunk.empty, a.paramHash)
    val inputsHashB = Hashing.inputsHash(b.id, bodyHashB, Chunk.empty, b.paramHash)
    assert(inputsHashA == inputsHashB)
  }
  "Task.cached[A, P] same id + different P produces different inputsHash" in {
    val factory     = Task.cached[String, Int]("compute") { p => s"out:$p" }
    val a           = factory(7).asInstanceOf[Task.Cached[String]]
    val b           = factory(9).asInstanceOf[Task.Cached[String]]
    val bodyHashA   = Hashing.bodyHash(a.id, a.version)
    val bodyHashB   = Hashing.bodyHash(b.id, b.version)
    val inputsHashA = Hashing.inputsHash(a.id, bodyHashA, Chunk.empty, a.paramHash)
    val inputsHashB = Hashing.inputsHash(b.id, bodyHashB, Chunk.empty, b.paramHash)
    assert(inputsHashA != inputsHashB)
  }
  "Task.cached[A, P] (no param) inputsHash matches unparameterized inputsHash" in {
    // An unparameterized cached task carries Maybe.empty for paramHash, and the
    // inputsHash must remain stable against the pre-parameterization world.
    val plain     = Task.cached[Int]("plain")(42).asInstanceOf[Task.Cached[Int]]
    val bodyHash  = Hashing.bodyHash(plain.id, plain.version)
    val hashed    = Hashing.inputsHash(plain.id, bodyHash, Chunk.empty, plain.paramHash)
    val baseline  = Hashing.inputsHash(plain.id, bodyHash, Chunk.empty)
    assert(hashed == baseline)
  }
  "Task.persistent[A, P] same id + different P produces different inputsHash" in {
    val factory     = Task.persistent[String, String]("persist") { p => s"out:$p" }
    val a           = factory("alpha").asInstanceOf[Task.Persistent[String]]
    val b           = factory("beta").asInstanceOf[Task.Persistent[String]]
    val bodyHashA   = Hashing.bodyHash(a.id, a.version)
    val bodyHashB   = Hashing.bodyHash(b.id, b.version)
    val inputsHashA = Hashing.inputsHash(a.id, bodyHashA, Chunk.empty, a.paramHash)
    val inputsHashB = Hashing.inputsHash(b.id, bodyHashB, Chunk.empty, b.paramHash)
    assert(inputsHashA != inputsHashB)
  }
  "Task.command[A, P] runs with parsed param" in {
    val factory: Int => Task.Command[String] =
      Task.command[String, Int]("serve") { p => s"port=$p" }
    val goal = factory(8080)
    for
      driver <- WorkflowTestDriver.init
      result <- driver.run(goal)
    yield assert(result == "port=8080")
  }
  "Task.command[A, P] keeps paramHash empty (commands never cache)" in {
    val factory = Task.command[String, Int]("serve") { p => s"port=$p" }
    val cmd     = factory(8080).asInstanceOf[Task.Command[String]]
    assert(cmd.paramHash == Maybe.empty)
  }
  "Task.cached[A, P, D1] (1 dep) folds P into inputsHash alongside dep fingerprints" in {
    val dep         = Task.cached[Int]("base")(5).asInstanceOf[Task.Cached[Int]]
    val factory     = Task.cached[String, Int, Int]("derive")(dep) { (p, d) => s"$p:$d" }
    val a           = factory(1).asInstanceOf[Task.Cached[String]]
    val b           = factory(2).asInstanceOf[Task.Cached[String]]
    val bodyHash    = Hashing.bodyHash(a.id, a.version)
    val depFp       = Chunk(Hashing.DepFingerprint(dep.id, Fingerprint.unsafe("blake3:stub")))
    val inputsHashA = Hashing.inputsHash(a.id, bodyHash, depFp, a.paramHash)
    val inputsHashB = Hashing.inputsHash(b.id, bodyHash, depFp, b.paramHash)
    assert(inputsHashA != inputsHashB)
  }
end ParameterizedTaskTests
