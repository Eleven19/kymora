package io.eleven19.kymora.workflow.internal

import kyo.*

/** JVM-only Blake3 hash binding.
  *
  * Backed by `io.github.rctcwyvrn:blake3:1.3` — a pure-Java single-jar
  * implementation (MIT, ~10 KiB). JS and Native variants live in
  * `src-js` / `src-native` sibling source trees and currently stub out —
  * a real cross-platform binding lands in plan Task 62.
  */
private[workflow] object Blake3:
  def hex(bytes: Chunk[Byte]): String =
    val h = io.github.rctcwyvrn.blake3.Blake3.newInstance()
    h.update(bytes.toArray)
    h.hexdigest()
end Blake3
