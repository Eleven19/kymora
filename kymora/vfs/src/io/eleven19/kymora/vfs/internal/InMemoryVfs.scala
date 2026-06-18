package io.eleven19.kymora.vfs.internal

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

import io.eleven19.kymora.vfs.*
import kyo.*

private[vfs] object InMemoryVfs:

    def init(using Frame): Vfs.Backend < Sync =
        AtomicRef.init(State.empty).map(ref => InMemoryVfs(ref))

    final private case class State(root: Node.Directory)

    private object State:

        val empty: State =
            State(Node.Directory(Map.empty, VfsTimestamp.epochMillis(0L)))
    end State

    private enum Node:
        case Directory(children: Map[String, Node], modified: VfsTimestamp)
        case File(bytes: Chunk[Byte], modified: VfsTimestamp)
        case Symlink(target: VPath, modified: VfsTimestamp)
    end Node
end InMemoryVfs

final private class InMemoryVfs private (
    state: AtomicRef[InMemoryVfs.State]
)(using Frame)
    extends Vfs.Backend:
    import InMemoryVfs.*

    def exists(path: VPath): Boolean < Sync =
        Sync.Unsafe.defer(lookup(state.unsafe.get().root, path).isRight)

    def isDirectory(path: VPath): Boolean < Sync =
        Sync.Unsafe.defer {
            lookup(state.unsafe.get().root, path) match
                case Right(Node.Directory(_, _)) => true
                case _                           => false
        }

    def isRegularFile(path: VPath): Boolean < Sync =
        Sync.Unsafe.defer {
            lookup(state.unsafe.get().root, path) match
                case Right(Node.File(_, _)) => true
                case _                      => false
        }

    def isSymbolicLink(path: VPath): Boolean < Sync =
        Sync.Unsafe.defer {
            lookup(state.unsafe.get().root, path) match
                case Right(Node.Symlink(_, _)) => true
                case _                         => false
        }

    def realPath(path: VPath): VPath < (Sync & Abort[VfsError]) =
        inspect(current => realPathFollow(current.root, path))

    def read(path: VPath): String < (Sync & Abort[VfsError]) =
        read(path, StandardCharsets.UTF_8)

    def read(path: VPath, charset: Charset): String < (Sync & Abort[VfsError]) =
        readBytesChunk(path).map(bytes => new String(bytes.toArray, charset))

    def readBytes(path: VPath): Span[Byte] < (Sync & Abort[VfsError]) =
        readBytesChunk(path).map(bytes => Span.from(bytes.toArray))

    def readLines(path: VPath): Chunk[String] < (Sync & Abort[VfsError]) =
        readLines(path, StandardCharsets.UTF_8)

    def readLines(path: VPath, charset: Charset): Chunk[String] < (Sync & Abort[VfsError]) =
        read(path, charset).map(value => Chunk.from(value.linesIterator.toSeq))

    def stat(path: VPath): VfsStat < (Sync & Abort[VfsError]) =
        inspect { current =>
            lookup(current.root, path).map {
                case Node.Directory(_, modified) =>
                    VfsStat(VfsEntryType.Directory, VfsSize.zero, modified)
                case Node.File(bytes, modified) =>
                    VfsStat(VfsEntryType.File, VfsSize.bytes(bytes.length.toLong), modified)
                case Node.Symlink(_, modified) =>
                    VfsStat(VfsEntryType.Symlink, VfsSize.zero, modified)
            }
        }

    def list(path: VPath): Chunk[VPath] < (Sync & Abort[VfsError]) =
        inspect { current =>
            lookup(current.root, path).flatMap {
                case Node.Directory(children, _) =>
                    Right(Chunk.from(children.keys.toSeq.sorted.map(name => path / name)))
                case Node.File(_, _) =>
                    Left(VfsError.NotDirectory(path))
                case Node.Symlink(_, _) =>
                    Left(VfsError.Unsupported(path, "list symlink"))
            }
        }

    def list(path: VPath, glob: String): Chunk[VPath] < (Sync & Abort[VfsError]) =
        if glob.contains("/") then Abort.fail(VfsError.Unsupported(path, "list glob with path separators"))
        else list(path).map(children => children.filter(child => child.name.exists(matchesGlob(glob, _))))

    def walk(
        path: VPath,
        maxDepth: Int = Int.MaxValue,
        followLinks: Boolean = false
    ): Stream[VPath, Sync & Scope & Abort[VfsError]] =
        Stream.init(inspect { current =>
            val found =
                if followLinks then lookupFollow(current.root, path)
                else lookup(current.root, path)
            found.flatMap {
                case directory: Node.Directory =>
                    walkDirectory(current.root, directory, path, maxDepth, followLinks)
                case Node.File(_, _) | Node.Symlink(_, _) =>
                    Right(Chunk.empty)
            }
        })

    def readSymlink(path: VPath): VPath < (Sync & Abort[VfsError]) =
        inspect { current =>
            lookup(current.root, path).flatMap {
                case Node.Symlink(target, _) => Right(target)
                case Node.Directory(_, _)    => Left(VfsError.IsDirectory(path))
                case Node.File(_, _)         => Left(VfsError.Unsupported(path, "readSymlink file"))
            }
        }

    def readStream(path: VPath): Stream[String, Sync & Scope & Abort[VfsError]] =
        readStream(path, StandardCharsets.UTF_8)

    def readStream(path: VPath, charset: Charset): Stream[String, Sync & Scope & Abort[VfsError]] =
        Stream.init(read(path, charset).map(value => Chunk(value)))

    def readBytesStream(path: VPath): Stream[Chunk[Byte], Sync & Scope & Abort[VfsError]] =
        Stream.init(readBytesChunk(path).map(bytes => Chunk(bytes)))

    def readLinesStream(path: VPath): Stream[String, Sync & Scope & Abort[VfsError]] =
        readLinesStream(path, StandardCharsets.UTF_8)

    def readLinesStream(path: VPath, charset: Charset): Stream[String, Sync & Scope & Abort[VfsError]] =
        Stream.init(readLines(path, charset))

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
        writeBytesChunk(path, Chunk.from(value.getBytes(charset).toSeq), createFolders)

    def writeBytes(
        path: VPath,
        value: Span[Byte],
        createFolders: Boolean = true
    ): Unit < (Sync & Abort[VfsError]) =
        writeBytesChunk(path, Chunk.from(value.toArray.toSeq), createFolders)

    def writeLines(
        path: VPath,
        value: Chunk[String],
        createFolders: Boolean = true
    ): Unit < (Sync & Abort[VfsError]) =
        write(path, renderLines(value), createFolders)

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
        appendBytesChunk(path, Chunk.from(value.getBytes(charset).toSeq), createFolders)

    def appendBytes(
        path: VPath,
        value: Span[Byte],
        createFolders: Boolean = true
    ): Unit < (Sync & Abort[VfsError]) =
        appendBytesChunk(path, Chunk.from(value.toArray.toSeq), createFolders)

    def appendLines(
        path: VPath,
        value: Chunk[String],
        createFolders: Boolean = true
    ): Unit < (Sync & Abort[VfsError]) =
        append(path, renderLines(value), StandardCharsets.UTF_8, createFolders)

    def truncate(path: VPath, size: VfsSize): Unit < (Sync & Abort[VfsError]) =
        val targetSize = size.toBytes
        if targetSize > Int.MaxValue then Abort.fail(VfsError.Unsupported(path, "truncate too large"))
        else
            now.flatMap { timestamp =>
                modify { current =>
                    updateNode(current.root, path.parts.toList, VPath.root) {
                        case Node.File(bytes, _) =>
                            val targetLength = targetSize.toInt
                            val updated =
                                if bytes.length >= targetLength then bytes.take(targetLength)
                                else bytes ++ Chunk.fill(targetLength - bytes.length)(0.toByte)
                            Right(Node.File(updated, timestamp))
                        case Node.Directory(_, _) =>
                            Left(VfsError.IsDirectory(path))
                        case Node.Symlink(_, _) =>
                            Left(VfsError.Unsupported(path, "truncate symlink"))
                    }.map(root => current.copy(root = root) -> ())
                }
            }

    def setLastModified(
        path: VPath,
        timestamp: VfsTimestamp
    ): Unit < (Sync & Abort[VfsError]) =
        modify { current =>
            updateNode(current.root, path.parts.toList, VPath.root) {
                case Node.Directory(children, _) => Right(Node.Directory(children, timestamp))
                case Node.File(bytes, _)         => Right(Node.File(bytes, timestamp))
                case Node.Symlink(target, _)     => Right(Node.Symlink(target, timestamp))
            }.map(root => current.copy(root = root) -> ())
        }

    def mkDir(path: VPath): Unit < (Sync & Abort[VfsError]) =
        now.flatMap { timestamp =>
            modify { current =>
                putDirectory(current.root, path.parts.toList, VPath.root, timestamp)
                    .map(root => current.copy(root = root) -> ())
            }
        }

    def mkFile(path: VPath): Unit < (Sync & Abort[VfsError]) =
        writeBytesChunk(path, Chunk.empty, createFolders = true)

    def move(
        from: VPath,
        to: VPath,
        replaceExisting: Boolean = false
    ): Unit < (Sync & Abort[VfsError]) =
        if isStrictDescendant(from, to) then
            Abort.fail(VfsError.InvalidPath(to.show, s"cannot move ${from.show} into its own descendant"))
        else
            now.flatMap { timestamp =>
                modify { current =>
                    for
                        node <- lookup(current.root, from)
                        withoutSource <-
                            removeAt(
                                current.root,
                                from.parts.toList,
                                VPath.root,
                                failIfMissing = true,
                                recursive = true,
                                timestamp
                            ).map(_._1)
                        withDest <- putNode(
                            withoutSource,
                            to.parts.toList,
                            VPath.root,
                            to,
                            node,
                            timestamp,
                            createFolders = true,
                            replaceExisting
                        )
                    yield current.copy(root = withDest) -> ()
                }
            }

    def copy(
        from: VPath,
        to: VPath,
        replaceExisting: Boolean = false
    ): Unit < (Sync & Abort[VfsError]) =
        now.flatMap { timestamp =>
            modify { current =>
                for
                    node <- lookup(current.root, from)
                    root <- putNode(
                        current.root,
                        to.parts.toList,
                        VPath.root,
                        to,
                        node,
                        timestamp,
                        createFolders = true,
                        replaceExisting
                    )
                yield current.copy(root = root) -> ()
            }
        }

    def remove(path: VPath): Boolean < (Sync & Abort[VfsError]) =
        now.flatMap { timestamp =>
            modify { current =>
                removeAt(
                    current.root,
                    path.parts.toList,
                    VPath.root,
                    failIfMissing = false,
                    recursive = false,
                    timestamp
                )
                    .map { case (root, removed) => current.copy(root = root) -> removed }
            }
        }

    def removeExisting(path: VPath): Unit < (Sync & Abort[VfsError]) =
        now.flatMap { timestamp =>
            modify { current =>
                removeAt(
                    current.root,
                    path.parts.toList,
                    VPath.root,
                    failIfMissing = true,
                    recursive = false,
                    timestamp
                )
                    .map { case (root, _) => current.copy(root = root) -> () }
            }
        }

    def removeAll(path: VPath): Unit < (Sync & Abort[VfsError]) =
        now.flatMap { timestamp =>
            modify { current =>
                removeAt(
                    current.root,
                    path.parts.toList,
                    VPath.root,
                    failIfMissing = false,
                    recursive = true,
                    timestamp
                )
                    .map { case (root, _) => current.copy(root = root) -> () }
            }
        }

    def createSymlink(path: VPath, target: VPath): Unit < (Sync & Abort[VfsError]) =
        now.flatMap { timestamp =>
            modify { current =>
                putNode(
                    current.root,
                    path.parts.toList,
                    VPath.root,
                    path,
                    Node.Symlink(target, timestamp),
                    timestamp,
                    createFolders = true,
                    replaceExisting = false
                ).map(root => current.copy(root = root) -> ())
            }
        }

    def writeStream(
        path: VPath,
        append: Boolean = false,
        createFolders: Boolean = true
    ): Vfs.WriteHandle < (Sync & Scope & Abort[VfsError]) =
        val init =
            if append then appendBytesChunk(path, Chunk.empty, createFolders)
            else writeBytesChunk(path, Chunk.empty, createFolders)
        init.andThen {
            val closed = java.util.concurrent.atomic.AtomicBoolean(false)
            Scope.ensure(Async.defer(closed.set(true))).map { _ =>
                new Vfs.WriteHandle:
                    def writeBytes(chunk: Chunk[Byte]): Unit < (Sync & Abort[VfsError]) =
                        if closed.get() then Abort.fail(VfsError.Unsupported(path, "writeStream.closed"))
                        else appendBytesChunk(path, chunk, createFolders)

                    def writeString(value: String): Unit < (Sync & Abort[VfsError]) =
                        writeString(value, StandardCharsets.UTF_8)

                    def writeString(
                        value: String,
                        charset: Charset
                    ): Unit < (Sync & Abort[VfsError]) =
                        writeBytes(Chunk.from(value.getBytes(charset).toSeq))
            }
        }

    private def readBytesChunk(path: VPath): Chunk[Byte] < (Sync & Abort[VfsError]) =
        inspect { current =>
            lookupFollow(current.root, path).flatMap {
                case Node.File(bytes, _)  => Right(bytes)
                case Node.Directory(_, _) => Left(VfsError.IsDirectory(path))
                case Node.Symlink(_, _)   => Left(VfsError.Unsupported(path, "read symlink"))
            }
        }

    private def writeBytesChunk(
        path: VPath,
        bytes: Chunk[Byte],
        createFolders: Boolean
    ): Unit < (Sync & Abort[VfsError]) =
        now.flatMap { timestamp =>
            modify { current =>
                putFile(current.root, path.parts.toList, VPath.root, path, bytes, timestamp, createFolders)
                    .map(root => current.copy(root = root) -> ())
            }
        }

    private def appendBytesChunk(
        path: VPath,
        bytes: Chunk[Byte],
        createFolders: Boolean
    ): Unit < (Sync & Abort[VfsError]) =
        now.flatMap { timestamp =>
            modify { current =>
                appendFile(current.root, path.parts.toList, VPath.root, path, bytes, timestamp, createFolders)
                    .map(root => current.copy(root = root) -> ())
            }
        }

    private def now: VfsTimestamp < Sync =
        VfsTimestamp.now

    private def inspect[A](operation: State => Either[VfsError, A]): A < (Sync & Abort[VfsError]) =
        Sync.Unsafe.defer(operation(state.unsafe.get())).flatMap(result => lift(result))

    private def modify[A](
        operation: State => Either[VfsError, (State, A)]
    ): A < (Sync & Abort[VfsError]) =
        Sync.Unsafe
            .defer {
                def loop(): Either[VfsError, A] =
                    val current = state.unsafe.get()
                    operation(current) match
                        case Left(error) =>
                            Left(error)
                        case Right((next, value)) =>
                            if state.unsafe.compareAndSet(current, next) then Right(value)
                            else loop()
                loop()
            }
            .flatMap(lift)

    private def lift[A](result: Either[VfsError, A]): A < Abort[VfsError] =
        result match
            case Right(value) => Abort.get(Result.succeed(value))
            case Left(error)  => Abort.fail(error)

    private def lookup(root: Node.Directory, path: VPath): Either[VfsError, Node] =
        lookupParts(root, path.parts.toList, path)

    private def lookupFollow(root: Node.Directory, path: VPath): Either[VfsError, Node] =
        lookupPartsFollow(root, root, path.parts.toList, VPath.root, path, Set.empty)

    private def realPathFollow(root: Node.Directory, path: VPath): Either[VfsError, VPath] =
        realPathPartsFollow(root, root, path.parts.toList, VPath.root, path, Set.empty)

    private def lookupParts(
        directory: Node.Directory,
        parts: List[String],
        path: VPath
    ): Either[VfsError, Node] =
        parts match
            case Nil =>
                Right(directory)
            case name :: rest =>
                directory.children.get(name) match
                    case None =>
                        Left(VfsError.NotFound(path))
                    case Some(child) if rest.isEmpty =>
                        Right(child)
                    case Some(next: Node.Directory) =>
                        lookupParts(next, rest, path)
                    case Some(_) =>
                        Left(VfsError.NotDirectory(path))

    private def realPathPartsFollow(
        root: Node.Directory,
        directory: Node.Directory,
        parts: List[String],
        currentPath: VPath,
        requestedPath: VPath,
        visited: Set[VPath]
    ): Either[VfsError, VPath] =
        parts match
            case Nil =>
                Right(currentPath)
            case name :: rest =>
                val childPath = currentPath / name
                directory.children.get(name) match
                    case None =>
                        Left(VfsError.NotFound(requestedPath))
                    case Some(Node.Symlink(target, _)) =>
                        if visited.contains(childPath) then Left(VfsError.SymlinkLoop(childPath))
                        else
                            val resolved = currentPath.resolve(target)
                            val nextPath = rest.foldLeft(resolved)(_ / _)
                            realPathPartsFollow(
                                root,
                                root,
                                nextPath.parts.toList,
                                VPath.root,
                                nextPath,
                                visited + childPath
                            )
                    case Some(_) if rest.isEmpty =>
                        Right(childPath)
                    case Some(next: Node.Directory) =>
                        realPathPartsFollow(root, next, rest, childPath, requestedPath, visited)
                    case Some(_) =>
                        Left(VfsError.NotDirectory(childPath))

    private def lookupPartsFollow(
        root: Node.Directory,
        directory: Node.Directory,
        parts: List[String],
        currentPath: VPath,
        requestedPath: VPath,
        visited: Set[VPath]
    ): Either[VfsError, Node] =
        parts match
            case Nil =>
                Right(directory)
            case name :: rest =>
                val linkPath = currentPath / name
                directory.children.get(name) match
                    case None =>
                        Left(VfsError.NotFound(requestedPath))
                    case Some(Node.Symlink(target, _)) =>
                        if visited.contains(linkPath) then Left(VfsError.SymlinkLoop(linkPath))
                        else
                            val resolved = currentPath.resolve(target)
                            val nextPath = rest.foldLeft(resolved)(_ / _)
                            lookupPartsFollow(
                                root,
                                root,
                                nextPath.parts.toList,
                                VPath.root,
                                nextPath,
                                visited + linkPath
                            )
                    case Some(node) if rest.isEmpty =>
                        Right(node)
                    case Some(next: Node.Directory) =>
                        lookupPartsFollow(root, next, rest, linkPath, requestedPath, visited)
                    case Some(_) =>
                        Left(VfsError.NotDirectory(linkPath))

    private def putFile(
        directory: Node.Directory,
        parts: List[String],
        currentPath: VPath,
        path: VPath,
        bytes: Chunk[Byte],
        timestamp: VfsTimestamp,
        createFolders: Boolean
    ): Either[VfsError, Node.Directory] =
        parts match
            case Nil =>
                Left(VfsError.IsDirectory(VPath.root))
            case name :: Nil =>
                directory.children.get(name) match
                    case Some(Node.Directory(_, _)) =>
                        Left(VfsError.IsDirectory(path))
                    case Some(Node.Symlink(_, _)) =>
                        Left(VfsError.Unsupported(path, "write symlink"))
                    case Some(Node.File(_, _)) | None =>
                        Right(
                            directory.copy(
                                children = directory.children.updated(name, Node.File(bytes, timestamp)),
                                modified = timestamp
                            )
                        )
            case name :: rest =>
                val childPath = currentPath / name
                putChildDirectory(directory, name, childPath, path, timestamp, createFolders) { child =>
                    putFile(child, rest, childPath, path, bytes, timestamp, createFolders)
                }

    private def appendFile(
        directory: Node.Directory,
        parts: List[String],
        currentPath: VPath,
        path: VPath,
        bytes: Chunk[Byte],
        timestamp: VfsTimestamp,
        createFolders: Boolean
    ): Either[VfsError, Node.Directory] =
        parts match
            case Nil =>
                Left(VfsError.IsDirectory(VPath.root))
            case name :: Nil =>
                directory.children.get(name) match
                    case Some(Node.Directory(_, _)) =>
                        Left(VfsError.IsDirectory(path))
                    case Some(Node.Symlink(_, _)) =>
                        Left(VfsError.Unsupported(path, "append symlink"))
                    case Some(Node.File(existing, _)) =>
                        Right(
                            directory.copy(
                                children = directory.children.updated(name, Node.File(existing ++ bytes, timestamp)),
                                modified = timestamp
                            )
                        )
                    case None =>
                        Right(
                            directory.copy(
                                children = directory.children.updated(name, Node.File(bytes, timestamp)),
                                modified = timestamp
                            )
                        )
            case name :: rest =>
                val childPath = currentPath / name
                putChildDirectory(directory, name, childPath, path, timestamp, createFolders) { child =>
                    appendFile(child, rest, childPath, path, bytes, timestamp, createFolders)
                }

    private def putDirectory(
        directory: Node.Directory,
        parts: List[String],
        currentPath: VPath,
        timestamp: VfsTimestamp
    ): Either[VfsError, Node.Directory] =
        parts match
            case Nil =>
                Right(directory.copy(modified = timestamp))
            case name :: rest =>
                val childPath = currentPath / name
                directory.children.get(name) match
                    case Some(Node.File(_, _)) =>
                        Left(VfsError.NotDirectory(childPath))
                    case Some(Node.Symlink(_, _)) =>
                        Left(VfsError.Unsupported(childPath, "mkDir symlink"))
                    case Some(child: Node.Directory) =>
                        putDirectory(child, rest, childPath, timestamp).map { updated =>
                            directory.copy(
                                children = directory.children.updated(name, updated),
                                modified = timestamp
                            )
                        }
                    case None =>
                        val child: Node.Directory = Node.Directory(Map.empty, timestamp)
                        putDirectory(child, rest, childPath, timestamp).map { updated =>
                            directory.copy(
                                children = directory.children.updated(name, updated),
                                modified = timestamp
                            )
                        }

    private def putNode(
        directory: Node.Directory,
        parts: List[String],
        currentPath: VPath,
        path: VPath,
        node: Node,
        timestamp: VfsTimestamp,
        createFolders: Boolean,
        replaceExisting: Boolean
    ): Either[VfsError, Node.Directory] =
        parts match
            case Nil =>
                node match
                    case replacement: Node.Directory if replaceExisting =>
                        Right(replacement.copy(modified = timestamp))
                    case _: Node.Directory =>
                        Left(VfsError.AlreadyExists(VPath.root))
                    case _ =>
                        Left(VfsError.IsDirectory(VPath.root))
            case name :: Nil =>
                directory.children.get(name) match
                    case Some(_) if !replaceExisting =>
                        Left(VfsError.AlreadyExists(path))
                    case _ =>
                        Right(
                            directory.copy(
                                children = directory.children.updated(name, node),
                                modified = timestamp
                            )
                        )
            case name :: rest =>
                val childPath = currentPath / name
                putChildDirectory(directory, name, childPath, path, timestamp, createFolders) { child =>
                    putNode(child, rest, childPath, path, node, timestamp, createFolders, replaceExisting)
                }

    private def putChildDirectory(
        directory: Node.Directory,
        name: String,
        childPath: VPath,
        requestedPath: VPath,
        timestamp: VfsTimestamp,
        createFolders: Boolean
    )(
        update: Node.Directory => Either[VfsError, Node.Directory]
    ): Either[VfsError, Node.Directory] =
        directory.children.get(name) match
            case Some(child: Node.Directory) =>
                update(child).map { updated =>
                    directory.copy(
                        children = directory.children.updated(name, updated),
                        modified = timestamp
                    )
                }
            case Some(Node.File(_, _)) =>
                Left(VfsError.NotDirectory(childPath))
            case Some(Node.Symlink(_, _)) =>
                Left(VfsError.Unsupported(childPath, "write symlink"))
            case None if createFolders =>
                update(Node.Directory(Map.empty, timestamp)).map { updated =>
                    directory.copy(
                        children = directory.children.updated(name, updated),
                        modified = timestamp
                    )
                }
            case None =>
                Left(VfsError.NotFound(requestedPath))

    private def updateNode(
        directory: Node.Directory,
        parts: List[String],
        currentPath: VPath
    )(
        update: Node => Either[VfsError, Node]
    ): Either[VfsError, Node.Directory] =
        parts match
            case Nil =>
                update(directory).flatMap {
                    case updated: Node.Directory => Right(updated)
                    case _                       => Left(VfsError.NotDirectory(VPath.root))
                }
            case name :: rest =>
                val childPath = currentPath / name
                directory.children.get(name) match
                    case None =>
                        Left(VfsError.NotFound(childPath))
                    case Some(child) if rest.isEmpty =>
                        update(child).map { updated =>
                            directory.copy(children = directory.children.updated(name, updated))
                        }
                    case Some(child: Node.Directory) =>
                        updateNode(child, rest, childPath)(update).map { updated =>
                            directory.copy(children = directory.children.updated(name, updated))
                        }
                    case Some(_) =>
                        Left(VfsError.NotDirectory(childPath))

    private def removeAt(
        directory: Node.Directory,
        parts: List[String],
        currentPath: VPath,
        failIfMissing: Boolean,
        recursive: Boolean,
        timestamp: VfsTimestamp
    ): Either[VfsError, (Node.Directory, Boolean)] =
        parts match
            case Nil =>
                if recursive then Right(Node.Directory(Map.empty, timestamp) -> true)
                else if directory.children.nonEmpty then Left(VfsError.DirectoryNotEmpty(VPath.root))
                else Right(directory -> false)
            case name :: Nil =>
                val childPath = currentPath / name
                directory.children.get(name) match
                    case None if failIfMissing =>
                        Left(VfsError.NotFound(childPath))
                    case None =>
                        Right(directory -> false)
                    case Some(Node.Directory(children, _)) if children.nonEmpty && !recursive =>
                        Left(VfsError.DirectoryNotEmpty(childPath))
                    case Some(_) =>
                        Right(directory.copy(children = directory.children.removed(name), modified = timestamp) -> true)
            case name :: rest =>
                val childPath = currentPath / name
                directory.children.get(name) match
                    case None if failIfMissing =>
                        Left(VfsError.NotFound(childPath))
                    case None =>
                        Right(directory -> false)
                    case Some(child: Node.Directory) =>
                        removeAt(child, rest, childPath, failIfMissing, recursive, timestamp).map {
                            case (updated, removed) =>
                                if removed then
                                    directory.copy(
                                        children = directory.children.updated(name, updated),
                                        modified = timestamp
                                    )          -> true
                                else directory -> false
                        }
                    case Some(_) =>
                        Left(VfsError.NotDirectory(childPath))

    private def walkDirectory(
        root: Node.Directory,
        directory: Node.Directory,
        path: VPath,
        maxDepth: Int,
        followLinks: Boolean
    ): Either[VfsError, Chunk[VPath]] =
        if maxDepth <= 0 then Right(Chunk.empty)
        else
            directory.children.toSeq.sortBy(_._1).foldLeft(Right(Chunk.empty): Either[VfsError, Chunk[VPath]]) {
                case (result, (name, node)) =>
                    result.flatMap { acc =>
                        val childPath  = path / name
                        val childChunk = Chunk(childPath)
                        val nodeResult =
                            if followLinks then lookupFollow(root, childPath)
                            else Right(node)
                        nodeResult.flatMap {
                            case childDirectory: Node.Directory =>
                                walkDirectory(root, childDirectory, childPath, maxDepth - 1, followLinks)
                                    .map(descendants => acc ++ childChunk ++ descendants)
                            case Node.File(_, _) | Node.Symlink(_, _) =>
                                Right(acc ++ childChunk)
                        }
                    }
            }

    private def renderLines(lines: Chunk[String]): String =
        if lines.isEmpty then ""
        else lines.map(_ + "\n").mkString

    private def isStrictDescendant(parent: VPath, child: VPath): Boolean =
        child.isAbsolute == parent.isAbsolute &&
            child.parts.length > parent.parts.length &&
            child.parts.take(parent.parts.length) == parent.parts

    private def matchesGlob(pattern: String, value: String): Boolean =
        val regex = pattern
            .split("\\*", -1)
            .map(java.util.regex.Pattern.quote)
            .mkString("^", ".*", "$")
        value.matches(regex)
end InMemoryVfs
