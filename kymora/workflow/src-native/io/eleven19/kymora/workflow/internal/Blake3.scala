package io.eleven19.kymora.workflow.internal

import kyo.*

/** Scala Native Blake3 stub.
  *
  * A real Scala Native binding lands in plan Task 62 (cross-platform Blake3 + smoke).
  * For now any call raises so Native callers fail loudly rather than producing a
  * silently-wrong hash.
  */
private[workflow] object Blake3:
  def hex(bytes: Chunk[Byte]): String =
    sys.error("Blake3 Native impl pending — see plan Task 62 (cross-platform Blake3)")
end Blake3
