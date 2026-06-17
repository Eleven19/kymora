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
end FingerprintTests
