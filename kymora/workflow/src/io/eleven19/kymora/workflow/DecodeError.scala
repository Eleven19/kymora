package io.eleven19.kymora.workflow

/** A failure surfaced by `Cacheable[A].decode` when the bytes cannot be turned back into a value of type `A` via the
  * runtime-provided `Codec`.
  *
  * Pure case class (no `Throwable` field) so it can be passed through Schema-derived plumbing later without dragging
  * JVM-only types around.
  */
final case class DecodeError(message: String) derives CanEqual

object DecodeError:

    def fromThrowable(t: Throwable): DecodeError =
        DecodeError(Option(t.getMessage).getOrElse(t.getClass.getSimpleName))
end DecodeError
