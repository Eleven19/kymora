package io.eleven19.kymora.vfs.internal

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

import io.eleven19.kymora.vfs.*
import kyo.*

private[vfs] object HostVfs:

    def init(root: Path)(using Frame): Vfs.Backend < Sync =
        Sync.defer(new HostVfs(root))

    /** Appends host path `segments` to `base`, preserving the OS path root.
      *
      * WORKAROUND: `kyo.Path`'s `/` operator (and `Path.apply`/`parts`) model paths as a driveless segment list, so on
      * Windows the drive letter is dropped and the rebuilt path re-anchors to the process working directory's drive
      * (e.g. `Path("C:/x") / "y"` renders as `D:/x/y` when the CWD is on `D:`). That corrupts every host path the VFS
      * derives from its root once the root and CWD live on different drives. Rebuilding from the rendered string keeps
      * the drive intact and is a no-op on POSIX.
      *
      * Upstream bug: getkyo/kyo#1678. Remove once it lands — tracked by Eleven19/kymora#3.
      */
    private[vfs] def resolve(base: Path, segments: String*): Path =
        if segments.isEmpty then base
        else
            val prefix = base.toString
            val sep    = if prefix.endsWith("/") then "" else "/"
            Path(s"$prefix$sep${segments.mkString("/")}")
end HostVfs

final private class HostVfs(root: Path)(using Frame) extends Vfs.Backend:

    def exists(path: VPath): Boolean < Sync =
        confinedMetadata(path)(_.exists).map(_.getOrElse(false))

    def isDirectory(path: VPath): Boolean < Sync =
        confinedMetadata(path)(_.isDirectory).map(_.getOrElse(false))

    def isRegularFile(path: VPath): Boolean < Sync =
        confinedMetadata(path)(_.isRegularFile).map(_.getOrElse(false))

    def isSymbolicLink(path: VPath): Boolean < Sync =
        confinedMetadata(path)(_ => hostPath(path).isSymbolicLink).map(_.getOrElse(false))

    def realPath(path: VPath): VPath < (Sync & Abort[VfsError]) =
        val virtual = absolute(path)
        for
            target <- confinedExisting(virtual, "realPath")
            result <- canonicalVirtual(target, virtual, "realPath")
        yield result

    def read(path: VPath): String < (Sync & Abort[VfsError]) =
        read(path, StandardCharsets.UTF_8)

    def read(path: VPath, charset: Charset): String < (Sync & Abort[VfsError]) =
        readOp(path, "read")(_.read(charset))

    def readBytes(path: VPath): Span[Byte] < (Sync & Abort[VfsError]) =
        readOp(path, "readBytes")(_.readBytes)

    def readLines(path: VPath): Chunk[String] < (Sync & Abort[VfsError]) =
        readLines(path, StandardCharsets.UTF_8)

    def readLines(path: VPath, charset: Charset): Chunk[String] < (Sync & Abort[VfsError]) =
        readOp(path, "readLines")(_.readLines(charset))

    def stat(path: VPath): VfsStat < (Sync & Abort[VfsError]) =
        val virtual = absolute(path)
        for
            target <- confinedExisting(virtual, "stat")
            exists <- target.exists(followLinks = false)
            result <-
                if !exists then Abort.fail(VfsError.NotFound(virtual))
                else
                    for
                        isLink <- target.isSymbolicLink
                        isDir  <- target.isDirectory
                        bytes <-
                            if isLink || isDir then Sync.defer(Span.empty[Byte])
                            else readPathOp(virtual, "stat", target)(_.readBytes)
                    yield
                        val entryType =
                            if isLink then VfsEntryType.Symlink
                            else if isDir then VfsEntryType.Directory
                            else VfsEntryType.File
                        val size =
                            if entryType == VfsEntryType.File then VfsSize.bytes(bytes.toArray.length.toLong)
                            else VfsSize.zero
                        // The pinned Kyo Path API exposes no cross-platform mtime yet.
                        VfsStat(entryType, size, VfsTimestamp.epochMillis(0L))
        yield result

    def list(path: VPath): Chunk[VPath] < (Sync & Abort[VfsError]) =
        val virtual = absolute(path)
        for
            target   <- confinedExisting(virtual, "list")
            children <- fsPathOp(virtual, "list", target)(_.list)
        yield childrenToVirtual(virtual, children)

    def list(path: VPath, glob: String): Chunk[VPath] < (Sync & Abort[VfsError]) =
        val virtual = absolute(path)
        for
            target   <- confinedExisting(virtual, "list")
            children <- fsPathOp(virtual, "list", target)(_.list(glob))
        yield childrenToVirtual(virtual, children)

    def walk(
        path: VPath,
        maxDepth: Int = Int.MaxValue,
        followLinks: Boolean = false
    ): Stream[VPath, Sync & Scope & Abort[VfsError]] =
        val virtual = absolute(path)
        Stream {
            val targetDepth =
                if maxDepth == Int.MaxValue then Int.MaxValue else maxDepth + 1
            for
                target <- confinedExisting(virtual, "walk")
                isDir  <- target.isDirectory
                result <-
                    if isDir then
                        Abort.run[FileFsException](
                            target
                                .walk(targetDepth, followLinks)
                                .filterPure(_ != target)
                                .map { child =>
                                    val virtualChild = walkVirtual(virtual, target, child)
                                    confinedHostPath(child, virtualChild, "walk").map(_ => virtualChild)
                                }
                                .emit
                        )
                    else Sync.defer(Result.succeed(()))
                _ <- result match
                    case Result.Success(_) => Sync.defer(())
                    case Result.Failure(error) =>
                        Abort.fail(translate(virtual, "walk", error))
                    case Result.Panic(error) =>
                        Abort.fail(VfsError.BackendFailure(virtual, "walk", error))
            yield ()
        }

    def readSymlink(path: VPath): VPath < (Sync & Abort[VfsError]) =
        Abort.fail(VfsError.Unsupported(absolute(path), "readSymlink"))

    def readStream(path: VPath): Stream[String, Sync & Scope & Abort[VfsError]] =
        readStream(path, StandardCharsets.UTF_8)

    def readStream(path: VPath, charset: Charset): Stream[String, Sync & Scope & Abort[VfsError]] =
        val virtual = absolute(path)
        Stream:
            for
                target <- confinedExisting(virtual, "readStream")
                _      <- translateReadStream(virtual, "readStream", target.readStream(charset)).emit
            yield ()

    def readBytesStream(path: VPath): Stream[Chunk[Byte], Sync & Scope & Abort[VfsError]] =
        val virtual = absolute(path)
        Stream:
            for
                target <- confinedExisting(virtual, "readBytesStream")
                _ <- translateReadStream(
                    virtual,
                    "readBytesStream",
                    target.readBytesStream.mapChunkPure(bytes => Seq(bytes))
                ).emit
            yield ()

    def readLinesStream(path: VPath): Stream[String, Sync & Scope & Abort[VfsError]] =
        readLinesStream(path, StandardCharsets.UTF_8)

    def readLinesStream(path: VPath, charset: Charset): Stream[String, Sync & Scope & Abort[VfsError]] =
        val virtual = absolute(path)
        Stream:
            for
                target <- confinedExisting(virtual, "readLinesStream")
                _      <- translateReadStream(virtual, "readLinesStream", target.readLinesStream(charset)).emit
            yield ()

    def write(
        path: VPath,
        value: String,
        createFolders: Boolean = true
    ): Unit < (Sync & Abort[VfsError]) =
        write(path, value, StandardCharsets.UTF_8, createFolders)

    override def write(
        path: VPath,
        value: String,
        charset: Charset
    ): Unit < (Sync & Abort[VfsError]) =
        write(path, value, charset, createFolders = true)

    def write(
        path: VPath,
        value: String,
        charset: Charset,
        createFolders: Boolean
    ): Unit < (Sync & Abort[VfsError]) =
        writeBytes(path, Span.from(value.getBytes(charset).toSeq), createFolders)

    def writeBytes(
        path: VPath,
        value: Span[Byte],
        createFolders: Boolean = true
    ): Unit < (Sync & Abort[VfsError]) =
        writeOp(path, "writeBytes")(_.writeBytes(value, createFolders))

    def writeLines(
        path: VPath,
        value: Chunk[String],
        createFolders: Boolean = true
    ): Unit < (Sync & Abort[VfsError]) =
        writeOp(path, "writeLines")(_.writeLines(value, createFolders))

    def append(
        path: VPath,
        value: String,
        createFolders: Boolean = true
    ): Unit < (Sync & Abort[VfsError]) =
        append(path, value, StandardCharsets.UTF_8, createFolders)

    override def append(
        path: VPath,
        value: String,
        charset: Charset
    ): Unit < (Sync & Abort[VfsError]) =
        append(path, value, charset, createFolders = true)

    def append(
        path: VPath,
        value: String,
        charset: Charset,
        createFolders: Boolean
    ): Unit < (Sync & Abort[VfsError]) =
        appendBytes(path, Span.from(value.getBytes(charset).toSeq), createFolders)

    def appendBytes(
        path: VPath,
        value: Span[Byte],
        createFolders: Boolean = true
    ): Unit < (Sync & Abort[VfsError]) =
        writeOp(path, "appendBytes")(_.appendBytes(value, createFolders))

    def appendLines(
        path: VPath,
        value: Chunk[String],
        createFolders: Boolean = true
    ): Unit < (Sync & Abort[VfsError]) =
        writeOp(path, "appendLines")(_.appendLines(value, createFolders))

    def truncate(path: VPath, size: VfsSize): Unit < (Sync & Abort[VfsError]) =
        writeExistingOp(path, "truncate")(_.truncate(size.toBytes))

    def setLastModified(
        path: VPath,
        timestamp: VfsTimestamp
    ): Unit < (Sync & Abort[VfsError]) =
        Abort.fail(VfsError.Unsupported(absolute(path), "setLastModified"))

    def mkDir(path: VPath): Unit < (Sync & Abort[VfsError]) =
        fsCreateOp(path, "mkDir")(_.mkDir)

    def mkFile(path: VPath): Unit < (Sync & Abort[VfsError]) =
        fsCreateOp(path, "mkFile")(_.mkFile)

    def move(
        from: VPath,
        to: VPath,
        replaceExisting: Boolean = false
    ): Unit < (Sync & Abort[VfsError]) =
        val source = absolute(from)
        val target = absolute(to)
        for
            sourcePath <- confinedExisting(source, "move")
            targetPath <- confinedForWrite(target, "move")
            _          <- fsPathOp(target, "move", sourcePath)(_.move(targetPath, replaceExisting = replaceExisting))
        yield ()

    def copy(
        from: VPath,
        to: VPath,
        replaceExisting: Boolean = false
    ): Unit < (Sync & Abort[VfsError]) =
        val source = absolute(from)
        val target = absolute(to)
        if source == target then Sync.defer(())
        else if isStrictDescendant(source, target) then
            Abort.fail(VfsError.InvalidPath(target.show, s"cannot copy ${source.show} into its own descendant"))
        else
            for
                sourcePath <- confinedExisting(source, "copy")
                targetPath <- confinedForWrite(target, "copy")
                _          <- copyHost(source, sourcePath, target, targetPath, replaceExisting)
            yield ()

    def remove(path: VPath): Boolean < (Sync & Abort[VfsError]) =
        fsExistingOrParentOp(path, "remove")(_.remove)

    def removeExisting(path: VPath): Unit < (Sync & Abort[VfsError]) =
        fsExistingOp(path, "removeExisting")(_.removeExisting)

    def removeAll(path: VPath): Unit < (Sync & Abort[VfsError]) =
        fsExistingOrParentOp(path, "removeAll")(_.removeAll)

    def createSymlink(path: VPath, target: VPath): Unit < (Sync & Abort[VfsError]) =
        Abort.fail(VfsError.Unsupported(absolute(path), "createSymlink"))

    def writeStream(
        path: VPath,
        append: Boolean = false,
        createFolders: Boolean = true
    ): Vfs.WriteHandle < (Sync & Scope & Abort[VfsError]) =
        val init =
            if append then appendBytes(path, Span.empty[Byte], createFolders)
            else writeBytes(path, Span.empty[Byte], createFolders)
        init.andThen {
            val closed = java.util.concurrent.atomic.AtomicBoolean(false)
            Scope.ensure(Async.defer(closed.set(true))).map { _ =>
                new Vfs.WriteHandle:
                    def writeBytes(chunk: Chunk[Byte]): Unit < (Sync & Abort[VfsError]) =
                        if closed.get() then Abort.fail(VfsError.Unsupported(absolute(path), "writeStream.closed"))
                        else appendBytes(path, Span.from(chunk.toArray), createFolders)

                    def writeString(value: String): Unit < (Sync & Abort[VfsError]) =
                        writeString(value, StandardCharsets.UTF_8)

                    def writeString(
                        value: String,
                        charset: Charset
                    ): Unit < (Sync & Abort[VfsError]) =
                        writeBytes(Chunk.from(value.getBytes(charset).toSeq))
            }
        }

    private def readOp[A](
        path: VPath,
        operation: String
    )(effect: Path => A < (Sync & Abort[FileReadException])): A < (Sync & Abort[VfsError]) =
        val virtual = absolute(path)
        confinedExisting(virtual, operation).flatMap(target => readPathOp(virtual, operation, target)(effect))

    private def readPathOp[A](
        path: VPath,
        operation: String,
        target: Path
    )(effect: Path => A < (Sync & Abort[FileReadException])): A < (Sync & Abort[VfsError]) =
        Abort.run[FileReadException](effect(target)).flatMap {
            case Result.Success(value) => Sync.defer(value)
            case Result.Failure(error) => Abort.fail(translate(path, operation, error))
            case Result.Panic(error)   => Abort.fail(VfsError.BackendFailure(path, operation, error))
        }

    private def translateReadStream[A](
        path: VPath,
        operation: String,
        stream: Stream[A, Sync & Scope & Abort[FileReadException]]
    ): Stream[A, Sync & Scope & Abort[VfsError]] =
        stream.handle { emit =>
            Abort.run[FileReadException](emit).flatMap {
                case Result.Success(_) => Sync.defer(())
                case Result.Failure(error) =>
                    Abort.fail(translate(path, operation, error))
                case Result.Panic(error) =>
                    Abort.fail(VfsError.BackendFailure(path, operation, error))
            }
        }

    private def writeOp[A](
        path: VPath,
        operation: String
    )(effect: Path => A < (Sync & Abort[FileWriteException])): A < (Sync & Abort[VfsError]) =
        val virtual = absolute(path)
        confinedForWrite(virtual, operation).flatMap(target => writePathOp(virtual, operation, target)(effect))

    private def writeExistingOp[A](
        path: VPath,
        operation: String
    )(effect: Path => A < (Sync & Abort[FileWriteException])): A < (Sync & Abort[VfsError]) =
        val virtual = absolute(path)
        confinedExisting(virtual, operation).flatMap(target => writePathOp(virtual, operation, target)(effect))

    private def writePathOp[A](
        path: VPath,
        operation: String,
        target: Path
    )(effect: Path => A < (Sync & Abort[FileWriteException])): A < (Sync & Abort[VfsError]) =
        Abort.run[FileWriteException](effect(target)).flatMap {
            case Result.Success(value) => Sync.defer(value)
            case Result.Failure(error) => Abort.fail(translate(path, operation, error))
            case Result.Panic(error)   => Abort.fail(VfsError.BackendFailure(path, operation, error))
        }

    private def fsCreateOp[A](
        path: VPath,
        operation: String
    )(effect: Path => A < (Sync & Abort[FileFsException])): A < (Sync & Abort[VfsError]) =
        val virtual = absolute(path)
        confinedForWrite(virtual, operation).flatMap(target => fsPathOp(virtual, operation, target)(effect))

    private def fsExistingOp[A](
        path: VPath,
        operation: String
    )(effect: Path => A < (Sync & Abort[FileFsException])): A < (Sync & Abort[VfsError]) =
        val virtual = absolute(path)
        confinedExisting(virtual, operation).flatMap(target => fsPathOp(virtual, operation, target)(effect))

    private def fsExistingOrParentOp[A](
        path: VPath,
        operation: String
    )(effect: Path => A < (Sync & Abort[FileFsException])): A < (Sync & Abort[VfsError]) =
        val virtual = absolute(path)
        hostPath(virtual).exists(followLinks = false).flatMap { exists =>
            val confined =
                if exists then confinedExisting(virtual, operation)
                else confinedForWrite(virtual, operation)
            confined.flatMap(target => fsPathOp(virtual, operation, target)(effect))
        }

    private def fsPathOp[A](
        path: VPath,
        operation: String,
        target: Path
    )(effect: Path => A < (Sync & Abort[FileFsException])): A < (Sync & Abort[VfsError]) =
        Abort.run[FileFsException](effect(target)).flatMap {
            case Result.Success(value) => Sync.defer(value)
            case Result.Failure(error) => Abort.fail(translate(path, operation, error))
            case Result.Panic(error)   => Abort.fail(VfsError.BackendFailure(path, operation, error))
        }

    private def confinedExisting(path: VPath, operation: String): Path < (Sync & Abort[VfsError]) =
        val virtual = absolute(path)
        Abort.run[FileException](hostPath(virtual).confinedTo(root)).flatMap {
            case Result.Success(value) => Sync.defer(value)
            case Result.Failure(error) => Abort.fail(translate(virtual, operation, error))
            case Result.Panic(error)   => Abort.fail(VfsError.BackendFailure(virtual, operation, error))
        }

    private def confinedForWrite(path: VPath, operation: String): Path < (Sync & Abort[VfsError]) =
        val virtual = absolute(path)
        val target  = hostPath(virtual)
        target.exists(followLinks = false).flatMap { exists =>
            if exists then confinedExisting(virtual, operation)
            else confineNearestExistingParent(virtual, operation).map(_ => target)
        }

    private def confineNearestExistingParent(path: VPath, operation: String): Unit < (Sync & Abort[VfsError]) =
        val parent = path.parent.getOrElse(VPath.root)
        confineNearestExisting(parent, operation)

    private def confineNearestExisting(path: VPath, operation: String): Unit < (Sync & Abort[VfsError]) =
        val virtual = absolute(path)
        hostPath(virtual).exists(followLinks = false).flatMap { exists =>
            if exists then
                confinedExisting(virtual, operation).flatMap { parentPath =>
                    parentPath.isDirectory.flatMap { isDirectory =>
                        if isDirectory then Sync.defer(())
                        else Abort.fail(VfsError.NotDirectory(virtual))
                    }
                }
            else
                virtual.parent match
                    case Present(parent) => confineNearestExisting(parent, operation)
                    case Absent          => confinedExisting(VPath.root, operation).map(_ => ())
        }

    private def confinedMetadata(path: VPath)(
        inspect: Path => Boolean < Sync
    ): Maybe[Boolean] < Sync =
        Abort.run[VfsError](confinedExisting(path, "metadata")).flatMap {
            case Result.Success(target) => inspect(target).map(Maybe(_))
            case Result.Failure(_)      => Sync.defer(Absent)
            case Result.Panic(_)        => Sync.defer(Absent)
        }

    private def copyHost(
        source: VPath,
        sourcePath: Path,
        target: VPath,
        targetPath: Path,
        replaceExisting: Boolean
    ): Unit < (Sync & Abort[VfsError]) =
        for
            targetExists <- targetPath.exists(followLinks = false)
            _ <-
                if targetExists && !replaceExisting then Abort.fail(VfsError.AlreadyExists(target))
                else if targetExists && replaceExisting then fsPathOp(target, "copy", targetPath)(_.removeAll)
                else Sync.defer(())
            isDirectory <- sourcePath.isDirectory
            _ <-
                if isDirectory then copyDirectory(source, sourcePath, target, targetPath, replaceExisting)
                else fsPathOp(target, "copy", sourcePath)(_.copy(targetPath, replaceExisting = replaceExisting))
        yield ()

    private def copyDirectory(
        source: VPath,
        sourcePath: Path,
        target: VPath,
        targetPath: Path,
        replaceExisting: Boolean
    ): Unit < (Sync & Abort[VfsError]) =
        for
            _        <- fsPathOp(target, "copy", targetPath)(_.mkDir)
            children <- fsPathOp(source, "copy", sourcePath)(_.list)
            _ <- Kyo.foreach(children) { child =>
                child.name match
                    case Present(name) =>
                        val childSource = source / name
                        val childTarget = target / name
                        for
                            confinedChild <- confinedHostPath(child, childSource, "copy")
                            targetChild = HostVfs.resolve(targetPath, name)
                            _ <- copyHost(childSource, confinedChild, childTarget, targetChild, replaceExisting)
                        yield ()
                    case Absent =>
                        Sync.defer(())
            }
        yield ()

    private def hostPath(path: VPath): Path =
        HostVfs.resolve(root, absolute(path).parts.toSeq*)

    private def absolute(path: VPath): VPath =
        if path.isAbsolute then path else VPath.root.resolve(path)

    private def isStrictDescendant(parent: VPath, child: VPath): Boolean =
        child.isAbsolute == parent.isAbsolute &&
            child.parts.length > parent.parts.length &&
            child.parts.take(parent.parts.length) == parent.parts

    private def childrenToVirtual(parent: VPath, children: Chunk[Path]): Chunk[VPath] =
        Chunk.from(
            children.toSeq
                .flatMap(_.name.toOption)
                .map(name => parent / name)
                .sortBy(_.show)
        )

    private def confinedHostPath(path: Path, virtual: VPath, operation: String): Path < (Sync & Abort[VfsError]) =
        Abort.run[FileException](path.confinedTo(root)).flatMap {
            case Result.Success(value) => Sync.defer(value)
            case Result.Failure(error) => Abort.fail(translate(virtual, operation, error))
            case Result.Panic(error)   => Abort.fail(VfsError.BackendFailure(virtual, operation, error))
        }

    private def canonicalVirtual(path: Path, virtual: VPath, operation: String): VPath < (Sync & Abort[VfsError]) =
        Abort.run[FileException](root.realPath).flatMap {
            case Result.Success(rootReal) =>
                Sync.defer(relativeVirtual(rootReal, path))
            case Result.Failure(error) =>
                Abort.fail(translate(virtual, operation, error))
            case Result.Panic(error) =>
                Abort.fail(VfsError.BackendFailure(virtual, operation, error))
        }

    private def relativeVirtual(base: Path, path: Path): VPath =
        path.parts.drop(base.parts.size).foldLeft(VPath.root)((current, part) => current / part)

    private def walkVirtual(start: VPath, startPath: Path, child: Path): VPath =
        child.parts.drop(startPath.parts.size).foldLeft(start)((current, part) => current / part)

    private def translate(path: VPath, operation: String, error: FileException): VfsError =
        error match
            case _: FileNotFoundException          => VfsError.NotFound(path)
            case _: FileAccessDeniedException      => VfsError.AccessDenied(path)
            case _: FileIsADirectoryException      => VfsError.IsDirectory(path)
            case _: FileNotADirectoryException     => VfsError.NotDirectory(path)
            case _: FileAlreadyExistsException     => VfsError.AlreadyExists(path)
            case _: FileDirectoryNotEmptyException => VfsError.DirectoryNotEmpty(path)
            case FileIOException(_, cause)         => VfsError.BackendFailure(path, operation, cause)
end HostVfs
