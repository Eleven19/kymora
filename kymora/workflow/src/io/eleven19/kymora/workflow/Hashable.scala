package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.internal.Canonical
import kyo.*

trait Hashable[A]:
  def hash(a: A): Fingerprint

object Hashable:
  /** Default: encode via Schema using the canonical wire format (independent of
    * Workflow.Config.codec) and blake3 the bytes. See spec §3.2.
    */
  given fromSchema[A](using s: Schema[A]): Hashable[A] =
    (a: A) => Fingerprint.ofBytes(Chunk.from(s.encode(a)(using Canonical).toArray))

  def apply[A](using h: Hashable[A]): Hashable[A] = h
end Hashable
