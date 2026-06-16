package io.eleven19.kymora.vfs.internal

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

import io.eleven19.kymora.vfs.*
import kyo.*

private[vfs] object MountedVfs:

    def init(mounts: Seq[Mount])(using Frame): Vfs < Abort[VfsError] =
        validate(mounts.map(mount => Entry(mount.at, mount.root, mount.vfs, Present(mount.vfs))))
            .map(entries => MountedWritableVfs(entries))

    def initReadonly(mounts: Seq[ReadonlyMount])(using Frame): ReadonlyVfs < Abort[VfsError] =
        validate(mounts.map(mount => Entry(mount.at, mount.root, mount.vfs, Absent)))
            .map(entries => MountedReadonlyVfs(entries))

    final private case class Entry(
        at: VPath,
        root: VPath,
        vfs: ReadonlyVfs,
        writable: Maybe[Vfs]
    )

    final private case class Route(
        entry: Entry,
        requested: VPath,
        translated: VPath
    )

    private def validate(entries: Seq[Entry])(using Frame): Chunk[Entry] < Abort[VfsError] =
        entries.find(entry => !entry.at.isAbsolute) match
            case Some(entry) =>
                Abort.fail(VfsError.InvalidPath(entry.at.show, "mount point must be absolute"))
            case None =>
                entries.find(entry => !entry.root.isAbsolute) match
                    case Some(entry) =>
                        Abort.fail(VfsError.InvalidPath(entry.root.show, "mount root must be absolute"))
                    case None =>
                        val duplicate = entries
                            .groupBy(entry => entry.at)
                            .collectFirst { case (path, duplicates) if duplicates.size > 1 => path }
                        duplicate match
                            case Some(path) =>
                                Abort.fail(VfsError.InvalidPath(path.show, "duplicate mount point"))
                            case None =>
                                Abort.get(
                                    Result.succeed(Chunk.from(entries.toSeq.sortBy(entry => -entry.at.parts.length)))
                                )

    private def syntheticStat(using Frame): VfsStat =
        VfsStat(VfsEntryType.Directory, VfsSize.zero, VfsTimestamp.epochMillis(0L))

    private def isPrefix(prefix: VPath, path: VPath): Boolean =
        prefix.isAbsolute == path.isAbsolute &&
            prefix.parts.length <= path.parts.length &&
            path.parts.take(prefix.parts.length) == prefix.parts

    private def pathFrom(base: VPath, parts: Chunk[String])(using Frame): VPath =
        parts.foldLeft(base)(_ / _)

    private def isStrictDescendant(parent: VPath, child: VPath): Boolean =
        child.isAbsolute == parent.isAbsolute &&
            child.parts.length > parent.parts.length &&
            child.parts.take(parent.parts.length) == parent.parts

    private def toMountSpace(route: Route, backendPath: VPath)(using Frame): Either[VfsError, VPath] =
        val root = route.entry.root
        if !backendPath.isAbsolute then Right(backendPath)
        else if isPrefix(root, backendPath) then
            Right(pathFrom(route.entry.at, backendPath.parts.drop(root.parts.length)))
        else Left(VfsError.AccessDenied(route.requested))

    private def toMountSpaceEffect(route: Route, backendPath: VPath)(using Frame): VPath < Abort[VfsError] =
        toMountSpace(route, backendPath) match
            case Right(path) => Abort.get(Result.succeed(path))
            case Left(error) => Abort.fail(error)

    private def translatePathError(
        route: Route,
        backendPath: VPath
    )(make: VPath => VfsError)(using Frame): VfsError =
        toMountSpace(route, backendPath) match
            case Right(path) => make(path)
            case Left(error) => error

    private def translateError(route: Route, operation: String, error: VfsError)(using Frame): VfsError =
        error match
            case VfsError.NotFound(path) =>
                translatePathError(route, path)(VfsError.NotFound.apply)
            case VfsError.AlreadyExists(path) =>
                translatePathError(route, path)(VfsError.AlreadyExists.apply)
            case VfsError.NotDirectory(path) =>
                translatePathError(route, path)(VfsError.NotDirectory.apply)
            case VfsError.IsDirectory(path) =>
                translatePathError(route, path)(VfsError.IsDirectory.apply)
            case VfsError.DirectoryNotEmpty(path) =>
                translatePathError(route, path)(VfsError.DirectoryNotEmpty.apply)
            case VfsError.AccessDenied(path) =>
                translatePathError(route, path)(VfsError.AccessDenied.apply)
            case VfsError.Unsupported(path, backendOperation) =>
                translatePathError(route, path)(translated => VfsError.Unsupported(translated, backendOperation))
            case VfsError.SymlinkLoop(path) =>
                translatePathError(route, path)(VfsError.SymlinkLoop.apply)
            case VfsError.BackendFailure(path, backendOperation, cause) =>
                translatePathError(route, path)(translated =>
                    VfsError.BackendFailure(translated, backendOperation, cause)
                )
            case VfsError.InvalidPath(input, reason) =>
                VfsError.InvalidPath(input, reason)
            case VfsError.NoHomeDirectory(input) =>
                VfsError.NoHomeDirectory(input)

    private def translateEffect[A](
        route: Route,
        operation: String
    )(effect: A < (Sync & Abort[VfsError]))(using Frame): A < (Sync & Abort[VfsError]) =
        Abort.run(effect).flatMap {
            case Result.Success(value) => Sync.defer(value)
            case Result.Failure(error) => Abort.fail(translateError(route, operation, error))
            case Result.Panic(error)   => Abort.fail(VfsError.BackendFailure(route.requested, operation, error))
        }

    private def translateScopedEffect[A](
        route: Route,
        operation: String
    )(effect: A < (Sync & Scope & Abort[VfsError]))(using Frame): A < (Sync & Scope & Abort[VfsError]) =
        Abort.run(effect).flatMap {
            case Result.Success(value) => Sync.defer(value)
            case Result.Failure(error) => Abort.fail(translateError(route, operation, error))
            case Result.Panic(error)   => Abort.fail(VfsError.BackendFailure(route.requested, operation, error))
        }

    private def translateStream[A](
        route: Route,
        operation: String,
        stream: Stream[A, Sync & Scope & Abort[VfsError]]
    )(using Frame): Stream[A, Sync & Scope & Abort[VfsError]] =
        stream.handle { emit =>
            Abort.run(emit).flatMap {
                case Result.Success(_) => Sync.defer(())
                case Result.Failure(error) =>
                    Abort.fail(translateError(route, operation, error))
                case Result.Panic(error) =>
                    Abort.fail(VfsError.BackendFailure(route.requested, operation, error))
            }
        }

    private def matchesGlob(pattern: String, value: String): Boolean =
        val regex = pattern
            .split("\\*", -1)
            .map(java.util.regex.Pattern.quote)
            .mkString("^", ".*", "$")
        value.matches(regex)

    private class MountedReadonlyVfs(entries: Chunk[Entry])(using Frame) extends ReadonlyVfs:

        protected def route(path: VPath): Maybe[Route] =
            entries.toSeq.find(entry => isPrefix(entry.at, path)) match
                case Some(entry) =>
                    val suffix     = path.parts.drop(entry.at.parts.length)
                    val translated = pathFrom(entry.root, suffix)
                    Present(Route(entry, path, translated))
                case None =>
                    Absent

        protected def routeOrNotFound(path: VPath): Route < Abort[VfsError] =
            route(path) match
                case Present(value) => Abort.get(Result.succeed(value))
                case Absent         => Abort.fail(VfsError.NotFound(path))

        private def syntheticChildren(path: VPath): Chunk[VPath] =
            val children = entries.toSeq.flatMap { entry =>
                if isPrefix(path, entry.at) && entry.at.parts.length > path.parts.length then
                    Some(pathFrom(path, Chunk(entry.at.parts(path.parts.length))))
                else None
            }
            Chunk.from(children.distinct.sortBy(_.show))

        private def hasSyntheticDirectory(path: VPath): Boolean =
            syntheticChildren(path).nonEmpty

        protected def isSyntheticDirectory(path: VPath): Boolean =
            hasSyntheticDirectory(path)

        private def mergeChildren(left: Chunk[VPath], right: Chunk[VPath]): Chunk[VPath] =
            Chunk.from((left.toSeq ++ right.toSeq).distinct.sortBy(_.show))

        private def backendList(route: Route): Chunk[VPath] < (Sync & Abort[VfsError]) =
            translateEffect(route, "list")(route.entry.vfs.list(route.translated))
                .flatMap { children =>
                    children.foldLeft(Sync.defer(Chunk.empty): Chunk[VPath] < (Sync & Abort[VfsError])) {
                        (accEffect, child) =>
                            for
                                acc        <- accEffect
                                translated <- toMountSpaceEffect(route, child)
                            yield acc ++ Chunk(translated)
                    }
                }

        private def walkChunk(
            path: VPath,
            maxDepth: Int,
            followLinks: Boolean
        ): Chunk[VPath] < (Sync & Scope & Abort[VfsError]) =
            if maxDepth <= 0 then Sync.defer(Chunk.empty)
            else if followLinks then
                route(path) match
                    case Present(found) if !hasSyntheticDirectory(path) =>
                        found.entry.vfs.isSymbolicLink(found.translated).flatMap { isLink =>
                            if isLink then
                                for
                                    realPath <- translateEffect(found, "realPath")(
                                        found.entry.vfs.realPath(found.translated)
                                    )
                                    _ <- toMountSpaceEffect(found, realPath)
                                    walked <- translateStream(
                                        found,
                                        "walk",
                                        found.entry.vfs.walk(found.translated, maxDepth, followLinks = true)
                                    )
                                        .map(child => toMountSpaceEffect(found, child))
                                        .run
                                yield walked
                            else
                                found.entry.vfs.isDirectory(found.translated).flatMap { isDirectory =>
                                    if isDirectory then walkChildren(path, maxDepth, followLinks)
                                    else Sync.defer(Chunk.empty)
                                }
                        }
                    case Absent =>
                        walkChildren(path, maxDepth, followLinks)
                    case Present(_) =>
                        walkChildren(path, maxDepth, followLinks)
            else walkChildren(path, maxDepth, followLinks)

        private def walkChildren(
            path: VPath,
            maxDepth: Int,
            followLinks: Boolean
        ): Chunk[VPath] < (Sync & Scope & Abort[VfsError]) =
            if maxDepth <= 0 then Sync.defer(Chunk.empty)
            else
                list(path).flatMap { children =>
                    children.foldLeft(Sync.defer(Chunk.empty): Chunk[VPath] < (Sync & Scope & Abort[VfsError])) {
                        (accEffect, child) =>
                            for
                                acc       <- accEffect
                                directory <- isDirectory(child)
                                nested <-
                                    if followLinks || directory then walkChunk(child, maxDepth - 1, followLinks)
                                    else Sync.defer(Chunk.empty)
                            yield acc ++ Chunk(child) ++ nested
                    }
                }

        def exists(path: VPath): Boolean < Sync =
            if hasSyntheticDirectory(path) then Sync.defer(true)
            else
                route(path) match
                    case Present(found) => found.entry.vfs.exists(found.translated)
                    case Absent         => Sync.defer(false)

        def isDirectory(path: VPath): Boolean < Sync =
            if hasSyntheticDirectory(path) then Sync.defer(true)
            else
                route(path) match
                    case Present(found) => found.entry.vfs.isDirectory(found.translated)
                    case Absent         => Sync.defer(false)

        def isRegularFile(path: VPath): Boolean < Sync =
            if hasSyntheticDirectory(path) then Sync.defer(false)
            else
                route(path) match
                    case Present(found) => found.entry.vfs.isRegularFile(found.translated)
                    case Absent         => Sync.defer(false)

        def isSymbolicLink(path: VPath): Boolean < Sync =
            if hasSyntheticDirectory(path) then Sync.defer(false)
            else
                route(path) match
                    case Present(found) => found.entry.vfs.isSymbolicLink(found.translated)
                    case Absent         => Sync.defer(false)

        def realPath(path: VPath): VPath < (Sync & Abort[VfsError]) =
            if hasSyntheticDirectory(path) then Sync.defer(path)
            else
                route(path) match
                    case Present(found) =>
                        translateEffect(found, "realPath")(found.entry.vfs.realPath(found.translated))
                            .flatMap(value => toMountSpaceEffect(found, value))
                    case Absent =>
                        Abort.fail(VfsError.NotFound(path))

        def read(path: VPath): String < (Sync & Abort[VfsError]) =
            read(path, StandardCharsets.UTF_8)

        def read(path: VPath, charset: Charset): String < (Sync & Abort[VfsError]) =
            if hasSyntheticDirectory(path) then Abort.fail(VfsError.IsDirectory(path))
            else
                routeOrNotFound(path).flatMap(found =>
                    translateEffect(found, "read")(found.entry.vfs.read(found.translated, charset))
                )

        def readBytes(path: VPath): Span[Byte] < (Sync & Abort[VfsError]) =
            if hasSyntheticDirectory(path) then Abort.fail(VfsError.IsDirectory(path))
            else
                routeOrNotFound(path).flatMap(found =>
                    translateEffect(found, "readBytes")(found.entry.vfs.readBytes(found.translated))
                )

        def readLines(path: VPath): Chunk[String] < (Sync & Abort[VfsError]) =
            readLines(path, StandardCharsets.UTF_8)

        def readLines(path: VPath, charset: Charset): Chunk[String] < (Sync & Abort[VfsError]) =
            if hasSyntheticDirectory(path) then Abort.fail(VfsError.IsDirectory(path))
            else
                routeOrNotFound(path).flatMap(found =>
                    translateEffect(found, "readLines")(found.entry.vfs.readLines(found.translated, charset))
                )

        def stat(path: VPath): VfsStat < (Sync & Abort[VfsError]) =
            if hasSyntheticDirectory(path) then Sync.defer(syntheticStat)
            else
                route(path) match
                    case Present(found) =>
                        Abort.run(translateEffect(found, "stat")(found.entry.vfs.stat(found.translated))).flatMap {
                            case Result.Success(value) =>
                                Sync.defer(value)
                            case Result.Failure(error) =>
                                Abort.fail(error)
                            case Result.Panic(error) =>
                                Abort.fail(VfsError.BackendFailure(path, "stat", error))
                        }
                    case Absent =>
                        Abort.fail(VfsError.NotFound(path))

        def list(path: VPath): Chunk[VPath] < (Sync & Abort[VfsError]) =
            val synthetic = syntheticChildren(path)
            route(path) match
                case Present(found) =>
                    Abort.run(backendList(found)).flatMap {
                        case Result.Success(children) =>
                            Sync.defer(mergeChildren(children, synthetic))
                        case Result.Failure(_: VfsError.NotFound) if synthetic.nonEmpty =>
                            Sync.defer(synthetic)
                        case Result.Failure(_: VfsError.NotDirectory) if synthetic.nonEmpty =>
                            Sync.defer(synthetic)
                        case Result.Failure(error) =>
                            Abort.fail(error)
                        case Result.Panic(error) =>
                            Abort.fail(VfsError.BackendFailure(path, "list", error))
                    }
                case Absent if synthetic.nonEmpty =>
                    Sync.defer(synthetic)
                case Absent =>
                    Abort.fail(VfsError.NotFound(path))

        def list(path: VPath, glob: String): Chunk[VPath] < (Sync & Abort[VfsError]) =
            if glob.contains("/") then Abort.fail(VfsError.Unsupported(path, "list glob with path separators"))
            else list(path).map(children => children.filter(child => child.name.exists(matchesGlob(glob, _))))

        def walk(
            path: VPath,
            maxDepth: Int = Int.MaxValue,
            followLinks: Boolean = false
        ): Stream[VPath, Sync & Scope & Abort[VfsError]] =
            Stream.init(walkChunk(path, maxDepth, followLinks))

        def readSymlink(path: VPath): VPath < (Sync & Abort[VfsError]) =
            if hasSyntheticDirectory(path) then Abort.fail(VfsError.IsDirectory(path))
            else
                routeOrNotFound(path).flatMap { found =>
                    translateEffect(found, "readSymlink")(found.entry.vfs.readSymlink(found.translated))
                        .flatMap(target => toMountSpaceEffect(found, target))
                }

        def readStream(path: VPath): Stream[String, Sync & Scope & Abort[VfsError]] =
            readStream(path, StandardCharsets.UTF_8)

        def readStream(path: VPath, charset: Charset): Stream[String, Sync & Scope & Abort[VfsError]] =
            Stream:
                if hasSyntheticDirectory(path) then Abort.fail(VfsError.IsDirectory(path))
                else
                    for
                        found <- routeOrNotFound(path)
                        _ <- translateStream(
                            found,
                            "readStream",
                            found.entry.vfs.readStream(found.translated, charset)
                        ).emit
                    yield ()

        def readBytesStream(path: VPath): Stream[Chunk[Byte], Sync & Scope & Abort[VfsError]] =
            Stream:
                if hasSyntheticDirectory(path) then Abort.fail(VfsError.IsDirectory(path))
                else
                    for
                        found <- routeOrNotFound(path)
                        _ <- translateStream(
                            found,
                            "readBytesStream",
                            found.entry.vfs.readBytesStream(found.translated)
                        ).emit
                    yield ()

        def readLinesStream(path: VPath): Stream[String, Sync & Scope & Abort[VfsError]] =
            readLinesStream(path, StandardCharsets.UTF_8)

        def readLinesStream(path: VPath, charset: Charset): Stream[String, Sync & Scope & Abort[VfsError]] =
            Stream:
                if hasSyntheticDirectory(path) then Abort.fail(VfsError.IsDirectory(path))
                else
                    for
                        found <- routeOrNotFound(path)
                        _ <- translateStream(
                            found,
                            "readLinesStream",
                            found.entry.vfs.readLinesStream(found.translated, charset)
                        ).emit
                    yield ()
    end MountedReadonlyVfs

    final private class MountedWritableVfs(entries: Chunk[Entry])(using Frame) extends MountedReadonlyVfs(entries), Vfs:

        private def writableRoute(path: VPath): (Route, Vfs) < Abort[VfsError] =
            routeOrNotFound(path).flatMap { found =>
                found.entry.writable match
                    case Present(vfs) => Abort.get(Result.succeed(found -> vfs))
                    case Absent       => Abort.fail(VfsError.Unsupported(path, "write"))
            }

        private def writeOp[A](
            path: VPath,
            operation: String
        )(effect: (Route, Vfs) => A < (Sync & Abort[VfsError])): A < (Sync & Abort[VfsError]) =
            if isSyntheticDirectory(path) then Abort.fail(VfsError.IsDirectory(path))
            else
                writableRoute(path).flatMap { case (found, vfs) =>
                    translateEffect(found, operation)(effect(found, vfs))
                }

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
            writeOp(path, "write")((found, vfs) => vfs.write(found.translated, value, charset, createFolders))

        def writeBytes(
            path: VPath,
            value: Span[Byte],
            createFolders: Boolean = true
        ): Unit < (Sync & Abort[VfsError]) =
            writeOp(path, "writeBytes")((found, vfs) => vfs.writeBytes(found.translated, value, createFolders))

        def writeLines(
            path: VPath,
            value: Chunk[String],
            createFolders: Boolean = true
        ): Unit < (Sync & Abort[VfsError]) =
            writeOp(path, "writeLines")((found, vfs) => vfs.writeLines(found.translated, value, createFolders))

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
            writeOp(path, "append")((found, vfs) => vfs.append(found.translated, value, charset, createFolders))

        def appendBytes(
            path: VPath,
            value: Span[Byte],
            createFolders: Boolean = true
        ): Unit < (Sync & Abort[VfsError]) =
            writeOp(path, "appendBytes")((found, vfs) => vfs.appendBytes(found.translated, value, createFolders))

        def appendLines(
            path: VPath,
            value: Chunk[String],
            createFolders: Boolean = true
        ): Unit < (Sync & Abort[VfsError]) =
            writeOp(path, "appendLines")((found, vfs) => vfs.appendLines(found.translated, value, createFolders))

        def truncate(path: VPath, size: VfsSize): Unit < (Sync & Abort[VfsError]) =
            writeOp(path, "truncate")((found, vfs) => vfs.truncate(found.translated, size))

        def setLastModified(
            path: VPath,
            timestamp: VfsTimestamp
        ): Unit < (Sync & Abort[VfsError]) =
            writeOp(path, "setLastModified")((found, vfs) => vfs.setLastModified(found.translated, timestamp))

        def mkDir(path: VPath): Unit < (Sync & Abort[VfsError]) =
            writeOp(path, "mkDir")((found, vfs) => vfs.mkDir(found.translated))

        def mkFile(path: VPath): Unit < (Sync & Abort[VfsError]) =
            writeOp(path, "mkFile")((found, vfs) => vfs.mkFile(found.translated))

        def move(
            from: VPath,
            to: VPath,
            replaceExisting: Boolean = false
        ): Unit < (Sync & Abort[VfsError]) =
            if isSyntheticDirectory(from) || isSyntheticDirectory(to) then Abort.fail(VfsError.IsDirectory(from))
            else
                for
                    source <- writableRoute(from)
                    target <- writableRoute(to)
                    _ <-
                        if isStrictDescendant(from, to) then
                            Abort.fail(
                                VfsError.InvalidPath(to.show, s"cannot move ${from.show} into its own descendant")
                            )
                        else if source._1.entry.at == target._1.entry.at then
                            translateEffect(source._1, "move")(
                                source._2.move(source._1.translated, target._1.translated, replaceExisting)
                            )
                        else Abort.fail(VfsError.Unsupported(from, "cross-mount move"))
                yield ()

        def copy(
            from: VPath,
            to: VPath,
            replaceExisting: Boolean = false
        ): Unit < (Sync & Abort[VfsError]) =
            if isSyntheticDirectory(from) || isSyntheticDirectory(to) then Abort.fail(VfsError.IsDirectory(from))
            else
                for
                    source <- writableRoute(from)
                    target <- writableRoute(to)
                    _ <-
                        if source._1.entry.at == target._1.entry.at then
                            translateEffect(source._1, "copy")(
                                source._2.copy(source._1.translated, target._1.translated, replaceExisting)
                            )
                        else Abort.fail(VfsError.Unsupported(from, "cross-mount copy"))
                yield ()

        def remove(path: VPath): Boolean < (Sync & Abort[VfsError]) =
            writeOp(path, "remove")((found, vfs) => vfs.remove(found.translated))

        def removeExisting(path: VPath): Unit < (Sync & Abort[VfsError]) =
            writeOp(path, "removeExisting")((found, vfs) => vfs.removeExisting(found.translated))

        def removeAll(path: VPath): Unit < (Sync & Abort[VfsError]) =
            writeOp(path, "removeAll")((found, vfs) => vfs.removeAll(found.translated))

        def createSymlink(path: VPath, target: VPath): Unit < (Sync & Abort[VfsError]) =
            if isSyntheticDirectory(path) then Abort.fail(VfsError.IsDirectory(path))
            else
                writableRoute(path).flatMap { case (found, vfs) =>
                    val translatedTarget =
                        if target.isAbsolute then
                            route(target) match
                                case Present(targetRoute) if targetRoute.entry.at == found.entry.at =>
                                    Abort.get(Result.succeed(targetRoute.translated))
                                case _ =>
                                    Abort.fail(VfsError.Unsupported(target, "cross-mount symlink target"))
                        else Sync.defer(target)
                    translatedTarget.flatMap { value =>
                        translateEffect(found, "createSymlink")(vfs.createSymlink(found.translated, value))
                    }
                }

        def writeStream(
            path: VPath,
            append: Boolean = false,
            createFolders: Boolean = true
        ): Vfs.WriteHandle < (Sync & Scope & Abort[VfsError]) =
            if isSyntheticDirectory(path) then Abort.fail(VfsError.IsDirectory(path))
            else
                writableRoute(path).flatMap { case (found, vfs) =>
                    translateScopedEffect(found, "writeStream")(
                        vfs.writeStream(found.translated, append, createFolders)
                    )
                        .map { handle =>
                            new Vfs.WriteHandle:
                                def writeBytes(chunk: Chunk[Byte]): Unit < (Sync & Abort[VfsError]) =
                                    translateEffect(found, "writeStream.writeBytes")(handle.writeBytes(chunk))

                                def writeString(value: String): Unit < (Sync & Abort[VfsError]) =
                                    writeString(value, StandardCharsets.UTF_8)

                                def writeString(
                                    value: String,
                                    charset: Charset
                                ): Unit < (Sync & Abort[VfsError]) =
                                    translateEffect(found, "writeStream.writeString")(
                                        handle.writeString(value, charset)
                                    )
                        }
                }
    end MountedWritableVfs
end MountedVfs
