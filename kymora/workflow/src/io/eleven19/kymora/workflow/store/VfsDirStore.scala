package io.eleven19.kymora.workflow.store

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.vfs.*
import kyo.*

/** Default cache store: Mill-style flat layout under a VFS directory.
  *
  *   - `<root>/<id-segments>.json`  — per-task manifest
  *   - `<root>/<id-segments>.dest/` — optional output dir for file-producing tasks
  *
  * This task ships `read` + `write` with atomic rename. `purge`, `remove`,
  * `list`, `openWorkspace`, `openPersistentWorkspace` are stubbed and will be
  * filled in Tasks 30-31.
  */
object VfsDirStore:

  /** Constructs a `CacheStore` rooted at `root` inside `vfs`. */
  def init(root: VPath, vfs: Vfs)(using Frame): CacheStore < (Async & Abort[StoreError]) =
    new Impl(root, vfs)

  /** Builds the absolute manifest path for `key` under `root`.
    *
    * The leaf segment of the key gets a `.json` suffix; intermediate segments
    * become directory components (Mill-style flat layout).
    */
  private def manifestPath(root: VPath, key: CacheKey)(using Frame): VPath =
    val parts = key.value.split('/').toVector.filter(_.nonEmpty)
    if parts.isEmpty then root / s".json"
    else
      val leaf     = parts.last + ".json"
      val dirParts = parts.dropRight(1)
      val parent   = dirParts.foldLeft(root)((p, seg) => p / seg)
      parent / leaf

  /** Sibling temp path used as the atomic-write staging file. */
  private def tempPath(p: VPath)(using Frame): VPath =
    p.parent match
      case Absent       => VPath(p.name.getOrElse("scratch") + ".tmp")
      case Present(par) => par / (p.name.getOrElse("scratch") + ".tmp")

  private final class Impl(root: VPath, vfs: Vfs) extends CacheStore:

    def read(key: CacheKey): Maybe[StoredManifest] < (Async & Abort[StoreError]) =
      val p = manifestPath(root, key)
      Abort.recover[VfsError](e => Abort.fail(StoreError.fromThrowable(p.show, e))):
        vfs.exists(p).map: exists =>
          if !exists then (Maybe.empty: Maybe[StoredManifest])
          else
            vfs.readBytes(p).map: span =>
              Maybe(StoredManifest(Chunk.from(span.toArray), p))

    def write(
        key: CacheKey,
        bytes: Chunk[Byte],
        destSeal: Maybe[VPath]
    ): Unit < (Async & Abort[StoreError]) =
      val p   = manifestPath(root, key)
      val tmp = tempPath(p)
      Abort.recover[VfsError](e => Abort.fail(StoreError.fromThrowable(p.show, e))):
        for
          _ <- vfs.writeBytes(tmp, Span.from(bytes.toArray), createFolders = true)
          _ <- vfs.move(tmp, p, replaceExisting = true)
        yield ()

    // Stubs — implemented in Tasks 30/31.
    def purge(): Unit < (Async & Abort[StoreError]) =
      Abort.fail(StoreError.IoFailure(root.show, "purge not yet implemented"))

    def remove(key: CacheKey): Unit < (Async & Abort[StoreError]) =
      Abort.fail(StoreError.IoFailure(root.show, "remove not yet implemented"))

    def list(prefix: String): Chunk[CacheKey] < (Async & Abort[StoreError]) =
      Abort.fail(StoreError.IoFailure(root.show, "list not yet implemented"))

    def openWorkspace(key: CacheKey): VPath < (Async & Scope & Abort[StoreError]) =
      Abort.fail(StoreError.IoFailure(root.show, "openWorkspace not yet implemented"))

    def openPersistentWorkspace(key: CacheKey): VPath < (Async & Scope & Abort[StoreError]) =
      Abort.fail(StoreError.IoFailure(root.show, "openPersistentWorkspace not yet implemented"))
  end Impl
end VfsDirStore
