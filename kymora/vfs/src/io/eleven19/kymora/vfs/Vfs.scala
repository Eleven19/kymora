package io.eleven19.kymora.vfs

import java.nio.charset.Charset

import kyo.*
import kyo.kernel.ContextEffect

/** Read-only virtual filesystem capability.
  *
  * APIs that only need to inspect files should depend on `ReadonlyVfs`. This makes the absence of write authority
  * explicit in the effect signature while still allowing callers to provide host, in-memory, mounted, or wrapped
  * writable implementations.
  *
  * {{{
  * def load(path: VPath)(using Frame): String < (ReadonlyVfs & Sync & Abort[VfsError]) =
  *     path.read
  *
  * val program =
  *     for
  *         backend <- Vfs.inMemory.init
  *         _       <- backend.write(VPath.root / "config.json", "{}")
  *         text    <- ReadonlyVfs.run(backend.asReadonly)(load(VPath.root / "config.json"))
  *     yield text
  * }}}
  */
sealed trait ReadonlyVfs extends ContextEffect[ReadonlyVfs.Backend]

/** Writable virtual filesystem capability.
  *
  * `Vfs` is intentionally separate from [[ReadonlyVfs]] so APIs can advertise the weakest filesystem authority they
  * need. [[Vfs.run]] handles both writable and read-only filesystem effects for ergonomic read/write programs.
  *
  * {{{
  * val program =
  *     for
  *         _    <- (VPath.root / "notes.txt").write("hello")
  *         text <- (VPath.root / "notes.txt").read
  *     yield text
  *
  * val result =
  *     for
  *         backend <- Vfs.inMemory.init
  *         text    <- Vfs.run(backend)(program)
  *     yield text
  * }}}
  */
sealed trait Vfs extends ContextEffect[Vfs.Backend]

/** Constructors and effect handlers for [[ReadonlyVfs]]. */
object ReadonlyVfs:

    /** Read-only virtual filesystem backend. */
    trait Backend:
        /** Returns whether a path exists. */
        def exists(path: VPath): Boolean < Sync

        /** Returns whether a path refers to a directory. */
        def isDirectory(path: VPath): Boolean < Sync

        /** Returns whether a path refers to a regular file. */
        def isRegularFile(path: VPath): Boolean < Sync

        /** Returns whether a path refers to a symbolic link. */
        def isSymbolicLink(path: VPath): Boolean < Sync

        /** Resolves symlinks and backend aliases to the canonical virtual path. */
        def realPath(path: VPath): VPath < (Sync & Abort[VfsError])

        /** Reads a text file as UTF-8. */
        def read(path: VPath): String < (Sync & Abort[VfsError])

        /** Reads a text file with the provided charset. */
        def read(path: VPath, charset: Charset): String < (Sync & Abort[VfsError])

        /** Reads a file as bytes. */
        def readBytes(path: VPath): Span[Byte] < (Sync & Abort[VfsError])

        /** Reads all lines from a UTF-8 text file. */
        def readLines(path: VPath): Chunk[String] < (Sync & Abort[VfsError])

        /** Reads all lines from a text file with the provided charset. */
        def readLines(path: VPath, charset: Charset): Chunk[String] < (Sync & Abort[VfsError])

        /** Returns metadata for a path. */
        def stat(path: VPath): VfsStat < (Sync & Abort[VfsError])

        /** Lists the direct children of a directory. */
        def list(path: VPath): Chunk[VPath] < (Sync & Abort[VfsError])

        /** Lists direct children of a directory matching a backend-specific glob. */
        def list(path: VPath, glob: String): Chunk[VPath] < (Sync & Abort[VfsError])

        /** Walks descendants of a path.
          *
          * The returned stream requires `Scope` because some backends may hold open handles while traversing. The
          * starting path itself is not emitted.
          */
        def walk(
            path: VPath,
            maxDepth: Int = Int.MaxValue,
            followLinks: Boolean = false
        ): Stream[VPath, Sync & Scope & Abort[VfsError]]

        /** Reads the target stored by a symbolic link. */
        def readSymlink(path: VPath): VPath < (Sync & Abort[VfsError])

        /** Streams a UTF-8 text file as backend-sized string chunks. */
        def readStream(path: VPath): Stream[String, Sync & Scope & Abort[VfsError]]

        /** Streams a text file as backend-sized string chunks. */
        def readStream(
            path: VPath,
            charset: Charset
        ): Stream[String, Sync & Scope & Abort[VfsError]]

        /** Streams a file as byte chunks. */
        def readBytesStream(path: VPath): Stream[Chunk[Byte], Sync & Scope & Abort[VfsError]]

        /** Streams a UTF-8 text file one line at a time. */
        def readLinesStream(path: VPath): Stream[String, Sync & Scope & Abort[VfsError]]

        /** Streams a text file one line at a time. */
        def readLinesStream(
            path: VPath,
            charset: Charset
        ): Stream[String, Sync & Scope & Abort[VfsError]]
    end Backend

    /** Retrieves the read-only filesystem backend from the current Kyo effect context. */
    def get(using Frame): Backend < ReadonlyVfs =
        ContextEffect.suspend(Tag[ReadonlyVfs])

    /** Provides a read-only filesystem backend to an effect requiring [[ReadonlyVfs]]. */
    def run[A, S](vfs: Backend)(value: A < (ReadonlyVfs & S))(using Frame): A < S =
        ContextEffect.handle(Tag[ReadonlyVfs], vfs)(value)

    /** Views a writable filesystem backend through the read-only API. */
    def from(vfs: Vfs.Backend): Backend =
        vfs

    /** Host-backed read-only filesystem constructors. */
    object host:

        /** Creates a host-backed VFS rooted at `root`.
          *
          * Operations are confined beneath `root` and exposed through virtual paths rooted at [[VPath.root]].
          */
        def init(root: Path)(using Frame): Backend < Sync =
            _root_.io.eleven19.kymora.vfs.internal.HostVfs.init(root).map(_.asReadonly)

    /** Mounted read-only filesystem constructors. */
    object mounted:

        /** Creates a read-only VFS by routing absolute mount points to backends. */
        def init(mounts: ReadonlyMount*)(using Frame): Backend < (Sync & Abort[VfsError]) =
            _root_.io.eleven19.kymora.vfs.internal.MountedVfs.initReadonly(mounts)
end ReadonlyVfs

/** Constructors, effect handlers, and streaming write support for [[Vfs]]. */
object Vfs:

    /** Writable virtual filesystem backend.
      *
      * Methods use `Abort[VfsError]` for recoverable filesystem failures rather than throwing backend exceptions.
      */
    trait Backend extends ReadonlyVfs.Backend:

        /** Narrows this writable filesystem backend to the read-only capability. */
        def asReadonly: ReadonlyVfs.Backend =
            ReadonlyVfs.from(this)

        /** Writes UTF-8 text, replacing any existing file. */
        def write(
            path: VPath,
            value: String,
            createFolders: Boolean = true
        ): Unit < (Sync & Abort[VfsError])

        /** Writes text with the provided charset, creating parent folders. */
        def write(
            path: VPath,
            value: String,
            charset: Charset
        ): Unit < (Sync & Abort[VfsError]) =
            write(path, value, charset, createFolders = true)

        /** Writes text with the provided charset, replacing any existing file. */
        def write(
            path: VPath,
            value: String,
            charset: Charset,
            createFolders: Boolean
        ): Unit < (Sync & Abort[VfsError])

        /** Writes bytes, replacing any existing file. */
        def writeBytes(
            path: VPath,
            value: Span[Byte],
            createFolders: Boolean = true
        ): Unit < (Sync & Abort[VfsError])

        /** Writes lines as UTF-8 text, replacing any existing file. */
        def writeLines(
            path: VPath,
            value: Chunk[String],
            createFolders: Boolean = true
        ): Unit < (Sync & Abort[VfsError])

        /** Appends UTF-8 text to a file. */
        def append(
            path: VPath,
            value: String,
            createFolders: Boolean = true
        ): Unit < (Sync & Abort[VfsError])

        /** Appends text with the provided charset, creating parent folders. */
        def append(
            path: VPath,
            value: String,
            charset: Charset
        ): Unit < (Sync & Abort[VfsError]) =
            append(path, value, charset, createFolders = true)

        /** Appends text with the provided charset to a file. */
        def append(
            path: VPath,
            value: String,
            charset: Charset,
            createFolders: Boolean
        ): Unit < (Sync & Abort[VfsError])

        /** Appends bytes to a file. */
        def appendBytes(
            path: VPath,
            value: Span[Byte],
            createFolders: Boolean = true
        ): Unit < (Sync & Abort[VfsError])

        /** Appends lines as UTF-8 text to a file. */
        def appendLines(
            path: VPath,
            value: Chunk[String],
            createFolders: Boolean = true
        ): Unit < (Sync & Abort[VfsError])

        /** Truncates or expands a file to the requested size. */
        def truncate(path: VPath, size: VfsSize): Unit < (Sync & Abort[VfsError])

        /** Sets the last-modified timestamp of an entry when supported. */
        def setLastModified(
            path: VPath,
            timestamp: VfsTimestamp
        ): Unit < (Sync & Abort[VfsError])

        /** Creates a single directory.
          *
          * Use [[mkDirs]] when the parent directories may not exist.
          */
        def mkDir(path: VPath): Unit < (Sync & Abort[VfsError])

        /** Creates this directory and any missing parents.
          *
          * Existing directories are accepted. If an existing path segment is a file or other non-directory entry, the
          * operation fails with [[VfsError.NotDirectory]].
          *
          * {{{
          * val out = VPath.root / "build" / "classes"
          * for
          *     vfs <- Vfs.get
          *     _   <- vfs.mkDirs(out)
          * yield out
          * }}}
          */
        def mkDirs(path: VPath)(using Frame): Unit < (Sync & Abort[VfsError]) =
            def ensureDirectory(existing: VPath): Unit < (Sync & Abort[VfsError]) =
                stat(existing).flatMap { metadata =>
                    if metadata.entryType == VfsEntryType.Directory then Sync.defer(())
                    else Abort.fail(VfsError.NotDirectory(existing))
                }

            def create(target: VPath): Unit < (Sync & Abort[VfsError]) =
                exists(target).flatMap { found =>
                    if found then ensureDirectory(target)
                    else
                        target.parent match
                            case Present(parent) =>
                                create(parent).andThen {
                                    Abort.run[VfsError](mkDir(target)).flatMap {
                                        case Result.Success(_) =>
                                            Sync.defer(())
                                        case Result.Failure(_: VfsError.AlreadyExists) =>
                                            ensureDirectory(target)
                                        case Result.Failure(error) =>
                                            Abort.fail(error)
                                        case Result.Panic(error) =>
                                            Abort.fail(VfsError.BackendFailure(target, "mkDirs", error))
                                    }
                                }
                            case Absent =>
                                Abort.run[VfsError](mkDir(target)).flatMap {
                                    case Result.Success(_) =>
                                        Sync.defer(())
                                    case Result.Failure(_: VfsError.AlreadyExists) =>
                                        ensureDirectory(target)
                                    case Result.Failure(error) =>
                                        Abort.fail(error)
                                    case Result.Panic(error) =>
                                        Abort.fail(VfsError.BackendFailure(target, "mkDirs", error))
                                }
                }

            create(path)

        /** Creates a scoped temporary directory inside this backend.
          *
          * The directory is created under `parent`, defaults to `/tmp`, and is removed recursively when the surrounding
          * [[Scope]] exits.
          *
          * {{{
          * Scope.run:
          *     for
          *         vfs <- Vfs.get
          *         dir <- vfs.tempDir(prefix = "compile-")
          *         _   <- vfs.write(dir / "output.txt", "ok")
          *     yield dir
          * }}}
          */
        def tempDir(
            parent: VPath = VPath.root / "tmp",
            prefix: String = "kymora-"
        )(using Frame): VPath < (Sync & Scope & Abort[VfsError]) =
            if prefix.isEmpty || prefix.contains("/") then
                Abort.fail(VfsError.InvalidPath(prefix, "tempDir prefix must be a non-empty single path segment"))
            else
                def loop(base: Long, attempt: Int): VPath < (Sync & Abort[VfsError]) =
                    val candidate = parent / s"$prefix$base-$attempt"
                    Abort.run[VfsError](mkDir(candidate)).flatMap {
                        case Result.Success(_) =>
                            Sync.defer(candidate)
                        case Result.Failure(_: VfsError.AlreadyExists) if attempt < 1024 =>
                            loop(base, attempt + 1)
                        case Result.Failure(_: VfsError.AlreadyExists) =>
                            Abort.fail(VfsError.Unsupported(parent, "tempDir exhausted unique names"))
                        case Result.Failure(error) =>
                            Abort.fail(error)
                        case Result.Panic(error) =>
                            Abort.fail(VfsError.BackendFailure(candidate, "tempDir", error))
                    }

                for
                    timestamp <- VfsTimestamp.now
                    _         <- mkDirs(parent)
                    dir       <- loop(timestamp.toEpochMillis, 0)
                    _         <- Scope.ensure(Abort.run(removeAll(dir))).unit
                yield dir

        /** Creates an empty file. */
        def mkFile(path: VPath): Unit < (Sync & Abort[VfsError])

        /** Moves an entry to a new path. */
        def move(
            from: VPath,
            to: VPath,
            replaceExisting: Boolean = false
        ): Unit < (Sync & Abort[VfsError])

        /** Copies an entry to a new path. */
        def copy(
            from: VPath,
            to: VPath,
            replaceExisting: Boolean = false
        ): Unit < (Sync & Abort[VfsError])

        /** Removes a file or empty directory, returning whether anything was removed. */
        def remove(path: VPath): Boolean < (Sync & Abort[VfsError])

        /** Removes a file or empty directory, failing when the path does not exist. */
        def removeExisting(path: VPath): Unit < (Sync & Abort[VfsError])

        /** Removes a file or directory tree recursively. */
        def removeAll(path: VPath): Unit < (Sync & Abort[VfsError])

        /** Creates a symbolic link at `path` pointing to `target`. */
        def createSymlink(path: VPath, target: VPath): Unit < (Sync & Abort[VfsError])

        /** Opens a scoped write handle for streaming writes.
          *
          * The returned handle is valid only within the surrounding `Scope`.
          */
        def writeStream(
            path: VPath,
            append: Boolean = false,
            createFolders: Boolean = true
        ): Vfs.WriteHandle < (Sync & Scope & Abort[VfsError])
    end Backend

    /** Retrieves the writable filesystem backend from the current Kyo effect context. */
    def get(using Frame): Backend < Vfs =
        ContextEffect.suspend(Tag[Vfs])

    /** Provides a writable filesystem backend to an effect requiring [[Vfs]] and, if needed, [[ReadonlyVfs]]. */
    def run[A, S](vfs: Backend)(value: A < (Vfs & ReadonlyVfs & S))(using Frame): A < S =
        ReadonlyVfs.run(vfs.asReadonly) {
            ContextEffect.handle(Tag[Vfs], vfs)(value)
        }

    /** A scoped host-backed temporary VFS and its virtual root. */
    final case class TempDir(vfs: Vfs.Backend, root: VPath)

    /** Creates a scoped temporary directory inside the active [[Vfs]].
      *
      * The returned directory is removed recursively when the surrounding [[Scope]] exits.
      */
    def tempDir(
        parent: VPath = VPath.root / "tmp",
        prefix: String = "kymora-"
    )(using Frame): VPath < (Sync & Scope & Vfs & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            dir <- vfs.tempDir(parent, prefix)
        yield dir

    /** Scoped destination for streaming writes. */
    trait WriteHandle:
        /** Writes a byte chunk to the stream. */
        def writeBytes(chunk: Chunk[Byte]): Unit < (Sync & Abort[VfsError])

        /** Writes UTF-8 text to the stream. */
        def writeString(value: String): Unit < (Sync & Abort[VfsError])

        /** Writes text with the provided charset to the stream. */
        def writeString(
            value: String,
            charset: Charset
        ): Unit < (Sync & Abort[VfsError])
    end WriteHandle

    /** In-memory filesystem constructors. */
    object inMemory:

        /** Creates an empty mutable in-memory filesystem. */
        def init(using Frame): Backend < Sync =
            _root_.io.eleven19.kymora.vfs.internal.InMemoryVfs.init

    /** Host-backed filesystem constructors. */
    object host:

        /** Creates a writable host-backed VFS rooted at `root`.
          *
          * Operations are confined beneath `root` and exposed through virtual paths rooted at [[VPath.root]].
          */
        def init(root: Path)(using Frame): Backend < Sync =
            _root_.io.eleven19.kymora.vfs.internal.HostVfs.init(root)

        /** Creates a scoped host-backed temporary VFS.
          *
          * The host directory is removed by Kyo when the surrounding [[Scope]] exits. The returned backend exposes that
          * host directory as virtual [[VPath.root]].
          *
          * {{{
          * Scope.run:
          *     for
          *         temp <- Vfs.host.tempDir()
          *         _    <- temp.vfs.write(temp.root / "scratch.txt", "ok")
          *     yield temp.root
          * }}}
          */
        def tempDir(
            prefix: String = "kymora-vfs-"
        )(using Frame): TempDir < (Sync & Scope & Abort[VfsError]) =
            if prefix.isEmpty || prefix.contains("/") then
                Abort.fail(VfsError.InvalidPath(prefix, "tempDir prefix must be a non-empty single path segment"))
            else
                Abort.recover[FileFsException](error =>
                    Abort.fail[VfsError](VfsError.BackendFailure(VPath.root, "host.tempDir", error))
                ):
                    for
                        root <- Path.tempDir(prefix)
                        vfs  <- init(root)
                    yield TempDir(vfs, VPath.root)

    /** Mounted filesystem constructors. */
    object mounted:

        /** Creates a writable VFS by routing absolute mount points to writable backends. */
        def init(mounts: Mount*)(using Frame): Backend < (Sync & Abort[VfsError]) =
            _root_.io.eleven19.kymora.vfs.internal.MountedVfs.init(mounts)
end Vfs

/** A writable mount entry for [[Vfs.mounted.init]].
  *
  * Requests under absolute path `at` are translated into `root` inside `vfs`.
  */
final case class Mount(at: VPath, vfs: Vfs.Backend, root: VPath = VPath.root)

/** A read-only mount entry for [[ReadonlyVfs.mounted.init]].
  *
  * Requests under absolute path `at` are translated into `root` inside `vfs`.
  */
final case class ReadonlyMount(at: VPath, vfs: ReadonlyVfs.Backend, root: VPath = VPath.root)

/** Effect-based syntax for using [[VPath]] values as filesystem operations. */
extension (path: VPath)

    /** Reads this path as UTF-8 text from [[ReadonlyVfs]]. */
    def read(using Frame): String < (Sync & ReadonlyVfs & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.read(path)
        yield value

    /** Reads this path as text from [[ReadonlyVfs]]. */
    def read(charset: Charset)(using Frame): String < (Sync & ReadonlyVfs & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.read(path, charset)
        yield value

    /** Reads this path as bytes from [[ReadonlyVfs]]. */
    def readBytes(using Frame): Span[Byte] < (Sync & ReadonlyVfs & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.readBytes(path)
        yield value

    /** Reads this path as UTF-8 lines from [[ReadonlyVfs]]. */
    def readLines(using Frame): Chunk[String] < (Sync & ReadonlyVfs & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.readLines(path)
        yield value

    /** Reads this path as lines from [[ReadonlyVfs]]. */
    def readLines(charset: Charset)(using Frame): Chunk[String] < (Sync & ReadonlyVfs & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.readLines(path, charset)
        yield value

    /** Returns metadata for this path from [[ReadonlyVfs]]. */
    def stat(using Frame): VfsStat < (Sync & ReadonlyVfs & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.stat(path)
        yield value

    /** Lists direct children of this path from [[ReadonlyVfs]]. */
    def list(using Frame): Chunk[VPath] < (Sync & ReadonlyVfs & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.list(path)
        yield value

    /** Lists direct children matching `glob` from [[ReadonlyVfs]]. */
    def list(glob: String)(using Frame): Chunk[VPath] < (Sync & ReadonlyVfs & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.list(path, glob)
        yield value

    /** Walks descendants of this path from [[ReadonlyVfs]]. */
    def walk(
        maxDepth: Int = Int.MaxValue,
        followLinks: Boolean = false
    )(using Frame): Stream[VPath, Sync & Scope & ReadonlyVfs & Abort[VfsError]] =
        Stream:
            for
                vfs <- ReadonlyVfs.get
                _   <- vfs.walk(path, maxDepth, followLinks).emit
            yield ()

    /** Reads the symlink target for this path from [[ReadonlyVfs]]. */
    def readSymlink(using Frame): VPath < (Sync & ReadonlyVfs & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.readSymlink(path)
        yield value

    /** Writes UTF-8 text to this path using [[Vfs]]. */
    def write(
        value: String,
        createFolders: Boolean = true
    )(using Frame): Unit < (Sync & Vfs & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.write(path, value, createFolders)
        yield ()

    /** Writes text to this path using [[Vfs]]. */
    def write(
        value: String,
        charset: Charset
    )(using Frame): Unit < (Sync & Vfs & Abort[VfsError]) =
        write(value, charset, createFolders = true)

    /** Writes text to this path using [[Vfs]], optionally creating parents. */
    def write(
        value: String,
        charset: Charset,
        createFolders: Boolean
    )(using Frame): Unit < (Sync & Vfs & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.write(path, value, charset, createFolders)
        yield ()

    /** Writes bytes to this path using [[Vfs]]. */
    def writeBytes(
        value: Span[Byte],
        createFolders: Boolean = true
    )(using Frame): Unit < (Sync & Vfs & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.writeBytes(path, value, createFolders)
        yield ()

    /** Writes UTF-8 lines to this path using [[Vfs]]. */
    def writeLines(
        value: Chunk[String],
        createFolders: Boolean = true
    )(using Frame): Unit < (Sync & Vfs & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.writeLines(path, value, createFolders)
        yield ()

    /** Appends UTF-8 text to this path using [[Vfs]]. */
    def append(
        value: String,
        createFolders: Boolean = true
    )(using Frame): Unit < (Sync & Vfs & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.append(path, value, createFolders)
        yield ()

    /** Appends text to this path using [[Vfs]]. */
    def append(
        value: String,
        charset: Charset
    )(using Frame): Unit < (Sync & Vfs & Abort[VfsError]) =
        append(value, charset, createFolders = true)

    /** Appends text to this path using [[Vfs]], optionally creating parents. */
    def append(
        value: String,
        charset: Charset,
        createFolders: Boolean
    )(using Frame): Unit < (Sync & Vfs & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.append(path, value, charset, createFolders)
        yield ()

    /** Appends bytes to this path using [[Vfs]]. */
    def appendBytes(
        value: Span[Byte],
        createFolders: Boolean = true
    )(using Frame): Unit < (Sync & Vfs & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.appendBytes(path, value, createFolders)
        yield ()

    /** Appends UTF-8 lines to this path using [[Vfs]]. */
    def appendLines(
        value: Chunk[String],
        createFolders: Boolean = true
    )(using Frame): Unit < (Sync & Vfs & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.appendLines(path, value, createFolders)
        yield ()

    /** Creates this path as a directory using [[Vfs]]. */
    def mkDir(using Frame): Unit < (Sync & Vfs & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.mkDir(path)
        yield ()

    /** Creates this path and any missing parents as directories using [[Vfs]]. */
    def mkDirs(using Frame): Unit < (Sync & Vfs & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.mkDirs(path)
        yield ()

    /** Creates a scoped temporary directory under this path using [[Vfs]].
      *
      * The returned directory is removed recursively when the surrounding [[Scope]] exits.
      */
    def tempDir(prefix: String = "kymora-")(using Frame): VPath < (Sync & Scope & Vfs & Abort[VfsError]) =
        Vfs.tempDir(parent = path, prefix = prefix)

    /** Removes this path recursively using [[Vfs]]. */
    def removeAll(using Frame): Unit < (Sync & Vfs & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.removeAll(path)
        yield ()
end extension
