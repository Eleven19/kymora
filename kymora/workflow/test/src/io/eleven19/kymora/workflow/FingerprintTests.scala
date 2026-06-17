package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class FingerprintTests extends Test[Any]:
  "Fingerprint round-trips its hex value" in {
    val fp = Fingerprint.unsafe("blake3:deadbeef")
    assert(fp.value == "blake3:deadbeef")
  }
  "Fingerprint is deterministic for equal byte sequences" in {
    val a = Chunk[Byte](1, 2, 3, 4)
    val b = Chunk[Byte](1, 2, 3, 4)
    assert(Fingerprint.ofBytes(a) == Fingerprint.ofBytes(b))
  }
  "Different bytes produce different fingerprints" in {
    assert(Fingerprint.ofBytes(Chunk[Byte](1, 2, 3)) != Fingerprint.ofBytes(Chunk[Byte](1, 2, 4)))
  }
  "Fingerprint.ofBytes produces canonical BLAKE3 hex (cross-platform stable)" in {
    // Canonical BLAKE3("hello") — byte-identical on JVM, Scala.js, Scala Native.
    val expected = "blake3:ea8f163db38682925e4491c5e58d4bb3506ef8c14eb78a86e908c5624a67200f"
    val fp       = Fingerprint.ofBytes(Chunk.from("hello".getBytes))
    assert(fp.value == expected)
  }
end FingerprintTests
