package io.eleven19.kymora.workflow.store

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.vfs.VPath
import kyo.*

/** The key the engine uses to look up a manifest. Mill-style:
  * dotted [[TaskId]] -> slash-separated path under the cache root.
  *
  * Example: `TaskId("kymora.vfs.jvm.compile")` -> `CacheKey("kymora/vfs/jvm/compile")`.
  */
final case class CacheKey(value: String) derives CanEqual
object CacheKey:
  def fromTaskId(id: TaskId): CacheKey = CacheKey(id.value.replace('.', '/'))
end CacheKey

/** A read manifest: encoded bytes plus the actual file path on disk
  * (useful for diagnostic errors). */
final case class StoredManifest(bytes: Chunk[Byte], path: VPath)

/** Pluggable cache store. Default impl is `VfsDirStore` in Task 29.
  *
  *   - `openWorkspace` returns a fresh `.dest.tmp/` workspace path for a
  *     `Task.Cached` body; the `Scope` finalizer cleans it up on failure,
  *     and the engine atomically renames it to `.dest/` on success.
  *   - `openPersistentWorkspace` returns the in-place `.dest/` for a
  *     `Task.Persistent` body, taking a per-key advisory lock for the
  *     `Scope`.
  */
trait CacheStore:
  def read(key: CacheKey): Maybe[StoredManifest] < (Async & Abort[StoreError])
  def write(
      key: CacheKey,
      bytes: Chunk[Byte],
      destSeal: Maybe[VPath],
  ): Unit < (Async & Abort[StoreError])
  def purge(): Unit < (Async & Abort[StoreError])
  def remove(key: CacheKey): Unit < (Async & Abort[StoreError])
  def list(prefix: String): Chunk[CacheKey] < (Async & Abort[StoreError])
  def openWorkspace(key: CacheKey): VPath < (Async & Scope & Abort[StoreError])
  def openPersistentWorkspace(key: CacheKey): VPath < (Async & Scope & Abort[StoreError])
end CacheStore
