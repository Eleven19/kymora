package io.eleven19.kymora.workflow.internal

import kyo.*

/** Cross-platform BLAKE3 binding.
  *
  * Backed by `pt.kcry::blake3` — a pure-Scala BLAKE3 implementation that
  * compiles for JVM, Scala.js, and Scala Native. Hashes are byte-identical
  * across platforms, so cache manifests written on one platform are valid
  * on any other.
  *
  * Returns a 64-character lowercase hex string (the canonical 32-byte
  * BLAKE3 digest).
  */
private[workflow] object Blake3:
  /** Canonical 32-byte BLAKE3 digest, hex-encoded (64 characters). */
  def hex(bytes: Chunk[Byte]): String =
    pt.kcry.blake3.Blake3.hex(bytes.toArray, 64)
end Blake3
