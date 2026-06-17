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

  /** Builds the path to the optional `<key>.dest/` workspace directory. */
  private def destPath(root: VPath, key: CacheKey)(using Frame): VPath =
    val parts = key.value.split('/').toVector.filter(_.nonEmpty)
    if parts.isEmpty then root / ".dest"
    else
      val leaf     = parts.last + ".dest"
      val dirParts = parts.dropRight(1)
      val parent   = dirParts.foldLeft(root)((p, seg) => p / seg)
      parent / leaf

  /** Converts an absolute path under `root` ending in `.json` back into a
    * [[CacheKey]]. Returns `Absent` for non-`.json` entries (directories,
    * sidecar files, etc.).
    */
  private def toCacheKey(root: VPath, path: VPath)(using Frame): Maybe[CacheKey] =
    val rootParts = root.parts
    val pathParts = path.parts
    if pathParts.size <= rootParts.size then Absent
    else if pathParts.take(rootParts.size) != rootParts then Absent
    else
      val tail = pathParts.drop(rootParts.size)
      tail.lastMaybe match
        case Absent => Absent
        case Present(leaf) if leaf.endsWith(".json") =>
          val stripped = leaf.stripSuffix(".json")
          if stripped.isEmpty then Absent
          else
            val segs = tail.dropRight(1) :+ stripped
            Present(CacheKey(segs.mkString("/")))
        case _ => Absent
    end if
  end toCacheKey

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

    /** Removes the entire cache root subtree.
      *
      * No-op if the root does not exist. Implemented as a recursive delete
      * via [[Vfs.removeAll]].
      */
    def purge(): Unit < (Async & Abort[StoreError]) =
      Abort.recover[VfsError](e => Abort.fail(StoreError.fromThrowable(root.show, e))):
        vfs.exists(root).map: exists =>
          if exists then vfs.removeAll(root)
          else ()

    /** Removes the manifest for `key` and any sibling `<key>.dest/` dir.
      *
      * Idempotent: missing files are silently ignored.
      */
    def remove(key: CacheKey): Unit < (Async & Abort[StoreError]) =
      val p    = manifestPath(root, key)
      val dest = destPath(root, key)
      Abort.recover[VfsError](e => Abort.fail(StoreError.fromThrowable(p.show, e))):
        for
          _              <- vfs.remove(p)
          destExists     <- vfs.exists(dest)
          _              <- if destExists then vfs.removeAll(dest) else (() : Unit < (Sync & Abort[VfsError]))
        yield ()

    /** Walks `root` and returns every known [[CacheKey]] whose path begins
      * with `prefix`.
      *
      * Walk-based (no on-disk index): each `.json` file under `root` is
      * reverse-engineered into a `CacheKey` by stripping the `.json` suffix
      * and computing the path relative to `root`.
      */
    def list(prefix: String): Chunk[CacheKey] < (Async & Abort[StoreError]) =
      Abort.recover[VfsError](e => Abort.fail(StoreError.fromThrowable(root.show, e))):
        vfs.exists(root).map: rootExists =>
          if !rootExists then Chunk.empty[CacheKey]
          else
            Scope.run(vfs.walk(root).run).map: entries =>
              entries.flatMap(toCacheKey(root, _)).filter(_.value.startsWith(prefix))

    def openWorkspace(key: CacheKey): VPath < (Async & Scope & Abort[StoreError]) =
      Abort.fail(StoreError.IoFailure(root.show, "openWorkspace not yet implemented"))

    def openPersistentWorkspace(key: CacheKey): VPath < (Async & Scope & Abort[StoreError]) =
      Abort.fail(StoreError.IoFailure(root.show, "openPersistentWorkspace not yet implemented"))
  end Impl
end VfsDirStore
