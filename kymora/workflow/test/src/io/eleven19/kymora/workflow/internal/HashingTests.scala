package io.eleven19.kymora.workflow.internal

import io.eleven19.kymora.workflow.*
import kyo.*
import kyo.test.*

class HashingTests extends Test[Any]:
  "bodyHash is stable for the same task id and version" in {
    val h1 = Hashing.bodyHash(TaskId("compile"), TaskVersion(1, 0, 0))
    val h2 = Hashing.bodyHash(TaskId("compile"), TaskVersion(1, 0, 0))
    assert(h1 == h2)
  }
  "bodyHash differs when task id differs" in {
    val a = Hashing.bodyHash(TaskId("compile"), TaskVersion.v1)
    val b = Hashing.bodyHash(TaskId("test"), TaskVersion.v1)
    assert(a != b)
  }
  "bodyHash differs when version differs" in {
    val a = Hashing.bodyHash(TaskId("compile"), TaskVersion(1, 0, 0))
    val b = Hashing.bodyHash(TaskId("compile"), TaskVersion(2, 0, 0))
    assert(a != b)
  }
  "inputsHash is stable for the same inputs" in {
    val deps = Chunk(
      Hashing.DepFingerprint(TaskId("a"), Fingerprint.unsafe("blake3:1")),
      Hashing.DepFingerprint(TaskId("b"), Fingerprint.unsafe("blake3:2"))
    )
    val a =
      Hashing.inputsHash(TaskId("compile"), Hashing.bodyHash(TaskId("compile"), TaskVersion.v1), deps)
    val b =
      Hashing.inputsHash(TaskId("compile"), Hashing.bodyHash(TaskId("compile"), TaskVersion.v1), deps)
    assert(a == b)
  }
  "inputsHash is permutation-invariant on deps (sorted by id)" in {
    val ordered = Chunk(
      Hashing.DepFingerprint(TaskId("a"), Fingerprint.unsafe("blake3:1")),
      Hashing.DepFingerprint(TaskId("b"), Fingerprint.unsafe("blake3:2"))
    )
    val reversed = Chunk(
      Hashing.DepFingerprint(TaskId("b"), Fingerprint.unsafe("blake3:2")),
      Hashing.DepFingerprint(TaskId("a"), Fingerprint.unsafe("blake3:1"))
    )
    val bh = Hashing.bodyHash(TaskId("compile"), TaskVersion.v1)
    val a  = Hashing.inputsHash(TaskId("compile"), bh, ordered)
    val b  = Hashing.inputsHash(TaskId("compile"), bh, reversed)
    assert(a == b)
  }
  "inputsHash changes when a dep's fingerprint changes" in {
    val depsA = Chunk(Hashing.DepFingerprint(TaskId("a"), Fingerprint.unsafe("blake3:1")))
    val depsB = Chunk(Hashing.DepFingerprint(TaskId("a"), Fingerprint.unsafe("blake3:2")))
    val bh    = Hashing.bodyHash(TaskId("compile"), TaskVersion.v1)
    val a     = Hashing.inputsHash(TaskId("compile"), bh, depsA)
    val b     = Hashing.inputsHash(TaskId("compile"), bh, depsB)
    assert(a != b)
  }
end HashingTests
