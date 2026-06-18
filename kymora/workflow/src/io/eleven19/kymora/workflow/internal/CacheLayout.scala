package io.eleven19.kymora.workflow.internal

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.store.*
import io.eleven19.kymora.vfs.*
import kyo.*

private[workflow] final case class CacheLayout(root: VPath):

  def manifestPath(key: CacheKey)(using Frame): VPath =
    val parts = key.value.split('/').toVector.filter(_.nonEmpty)
    if parts.isEmpty then root / ".json"
    else
      val parent = parts.dropRight(1).foldLeft(root)(_ / _)
      parent / s"${parts.last}.json"

  def tempPath(path: VPath)(using Frame): VPath =
    path.parent match
      case Absent       => VPath(path.name.getOrElse("scratch") + ".tmp")
      case Present(par) => par / (path.name.getOrElse("scratch") + ".tmp")

  def destPath(key: CacheKey)(using Frame): VPath =
    val parts = key.value.split('/').toVector.filter(_.nonEmpty)
    if parts.isEmpty then root / ".dest"
    else
      val parent = parts.dropRight(1).foldLeft(root)(_ / _)
      parent / s"${parts.last}.dest"

  def destTmpPath(key: CacheKey)(using Frame): VPath =
    val parts = key.value.split('/').toVector.filter(_.nonEmpty)
    if parts.isEmpty then root / ".dest.tmp"
    else
      val parent = parts.dropRight(1).foldLeft(root)(_ / _)
      parent / s"${parts.last}.dest.tmp"

  def readManifest(
      vfs: Vfs.Backend,
      key: CacheKey,
  )(using Frame): Maybe[StoredManifest] < (Sync & Abort[WorkflowError]) =
    val path = manifestPath(key)
    recover(path):
      vfs.exists(path).flatMap { exists =>
        if !exists then Maybe.empty[StoredManifest]
        else vfs.readBytes(path).map(bytes => Maybe(StoredManifest(Chunk.from(bytes.toArray), path)))
      }

  def writeManifest(
      vfs: Vfs.Backend,
      key: CacheKey,
      bytes: Chunk[Byte],
  )(using Frame): Unit < (Sync & Abort[WorkflowError]) =
    val path = manifestPath(key)
    val tmp  = tempPath(path)
    recover(path):
      for
        _ <- vfs.writeBytes(tmp, Span.from(bytes.toArray), createFolders = true)
        _ <- vfs.move(tmp, path, replaceExisting = true)
      yield ()

  def openWorkspace(
      vfs: Vfs.Backend,
      key: CacheKey,
  )(using Frame): VPath < (Sync & Scope & Abort[WorkflowError]) =
    val path = destTmpPath(key)
    recover(path):
      for
        _ <- vfs.removeAll(path)
        _ <- vfs.mkDir(path)
        _ <- Scope.ensure(Abort.run(vfs.removeAll(path))).unit
      yield path

  def sealWorkspace(
      vfs: Vfs.Backend,
      key: CacheKey,
  )(using Frame): Unit < (Sync & Abort[WorkflowError]) =
    val tmp  = destTmpPath(key)
    val dest = destPath(key)
    recover(dest):
      for
        exists <- vfs.exists(tmp)
        _      <-
          if !exists then (() : Unit < (Sync & Abort[VfsError]))
          else vfs.move(tmp, dest, replaceExisting = true)
      yield ()

  def openPersistentWorkspace(
      vfs: Vfs.Backend,
      key: CacheKey,
  )(using Frame): VPath < (Sync & Abort[WorkflowError]) =
    val path = destPath(key)
    recover(path):
      for
        exists <- vfs.exists(path)
        _      <- if exists then (() : Unit < (Sync & Abort[VfsError])) else vfs.mkDir(path)
      yield path

  def purge(vfs: Vfs.Backend)(using Frame): Unit < (Sync & Abort[WorkflowError]) =
    recover(root):
      for
        exists <- vfs.exists(root)
        _      <- if exists then vfs.removeAll(root) else (() : Unit < (Sync & Abort[VfsError]))
      yield ()

  def remove(vfs: Vfs.Backend, key: CacheKey)(using Frame): Unit < (Sync & Abort[WorkflowError]) =
    val manifest = manifestPath(key)
    val dest     = destPath(key)
    val tmp      = destTmpPath(key)
    recover(manifest):
      for
        _          <- vfs.remove(manifest)
        destExists <- vfs.exists(dest)
        _          <- if destExists then vfs.removeAll(dest) else (() : Unit < (Sync & Abort[VfsError]))
        tmpExists  <- vfs.exists(tmp)
        _          <- if tmpExists then vfs.removeAll(tmp) else (() : Unit < (Sync & Abort[VfsError]))
      yield ()

  def list(vfs: Vfs.Backend, prefix: String)(using Frame): Chunk[CacheKey] < (Async & Abort[WorkflowError]) =
    recover(root):
      vfs.exists(root).flatMap { exists =>
        if !exists then Chunk.empty[CacheKey]
        else
          Scope.run(vfs.walk(root).run).map { entries =>
            entries.flatMap(toCacheKey).filter(_.value.startsWith(prefix))
          }
      }

  private def toCacheKey(path: VPath)(using Frame): Maybe[CacheKey] =
    val rootParts = root.parts
    val pathParts = path.parts
    if pathParts.size <= rootParts.size || pathParts.take(rootParts.size) != rootParts then Absent
    else
      val tail = pathParts.drop(rootParts.size)
      tail.lastMaybe match
        case Present(leaf) if leaf.endsWith(".json") =>
          val stripped = leaf.stripSuffix(".json")
          if stripped.isEmpty then Absent
          else Present(CacheKey((tail.dropRight(1) :+ stripped).mkString("/")))
        case _ => Absent

  private def recover[A, S](
      path: VPath,
  )(value: A < (S & Abort[VfsError]))(using Frame): A < (S & Abort[WorkflowError]) =
    Abort.recover[VfsError](error =>
      Abort.fail[WorkflowError](WorkflowError.Store(StoreError.fromThrowable(path.show, error)))
    )(value)
end CacheLayout
