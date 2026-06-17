package io.eleven19.kymora.workflow

import kyo.*

/** Errors raised by the cache + manifest storage layer.
  *
  * Sealed + `Schema`-derived so storage failures can be persisted alongside
  * results (e.g. in negative-cache entries) and round-tripped through the
  * observability bus.
  */
sealed trait StoreError derives CanEqual, Schema
object StoreError:
  final case class NotFound(key: String)                                  extends StoreError
  final case class CorruptManifest(path: String, reason: String)          extends StoreError
  final case class IoFailure(path: String, message: String)               extends StoreError
  final case class LockTaken(holder: String)                              extends StoreError
  final case class UnknownSchemaVersion(found: Int, supported: Int)       extends StoreError

  /** Lift an arbitrary `Throwable` from an I/O boundary into a `StoreError`,
    * preferring `getMessage` over the class name when available.
    */
  def fromThrowable(path: String, t: Throwable): StoreError =
    IoFailure(path, Option(t.getMessage).getOrElse(t.getClass.getSimpleName))
end StoreError
