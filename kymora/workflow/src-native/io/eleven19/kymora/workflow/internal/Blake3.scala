package io.eleven19.kymora.workflow.internal

import kyo.*

/** Scala Native cross-platform hash fallback.
  *
  * '''Not''' a real BLAKE3. To avoid an FFI binding to a native BLAKE3
  * library this ships a deterministic '''FNV-1a 64-bit''' digest that
  * compiles on every Scala 3 backend with zero platform deps.
  *
  * The object is still named `Blake3` to keep the platform-shared call site
  * (`Blake3.hex(bytes)`) intact — the API contract is "deterministic hex
  * digest of a byte chunk", not "cryptographic security".
  *
  * '''Known limitation:''' hashes produced here do NOT match the BLAKE3
  * hashes produced by the JVM binding. Cache files written under one
  * platform are therefore '''not''' valid under another. Cross-platform
  * cache sharing is not a v1 target; revisit when shipping a real
  * cross-platform BLAKE3 port (plan Task 62 follow-up).
  */
private[workflow] object Blake3:
  def hex(bytes: Chunk[Byte]): String =
    val FnvOffset = 0xcbf29ce484222325L
    val FnvPrime  = 0x100000001b3L
    var hash      = FnvOffset
    val arr       = bytes.toArray
    var i         = 0
    while i < arr.length do
      hash = (hash ^ (arr(i) & 0xffL)) * FnvPrime
      i += 1
    "%016x".format(hash)
end Blake3
