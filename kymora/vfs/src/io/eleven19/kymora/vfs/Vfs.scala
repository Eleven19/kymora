package io.eleven19.kymora.vfs

import java.nio.charset.Charset

import kyo.*

/** Read-only virtual filesystem capability.
  *
  * APIs that only need to inspect files should depend on `Env[ReadonlyVfs]`. This makes the absence of write authority
  * explicit in the effect signature while still allowing callers to provide host, in-memory, mounted, or wrapped
  * writable implementations.
  */
trait ReadonlyVfs:
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
      * The returned stream requires `Scope` because some backends may hold open handles while traversing. The starting
      * path itself is not emitted.
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
end ReadonlyVfs

/** Writable virtual filesystem capability.
  *
  * `Vfs` extends [[ReadonlyVfs]], so writable implementations can be passed to read-only APIs through [[asReadonly]].
  * Methods use `Abort[VfsError]` for recoverable filesystem failures rather than throwing backend exceptions.
  */
trait Vfs extends ReadonlyVfs:

    /** Narrows this writable filesystem to the read-only capability. */
    def asReadonly: ReadonlyVfs =
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

    /** Creates a directory. */
    def mkDir(path: VPath): Unit < (Sync & Abort[VfsError])

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
end Vfs

/** Constructors and environment helpers for [[ReadonlyVfs]]. */
object ReadonlyVfs:

    /** Retrieves the read-only filesystem from the current Kyo environment. */
    def get(using Frame): ReadonlyVfs < Env[ReadonlyVfs] =
        Env.get[ReadonlyVfs]

    /** Provides a read-only filesystem to an effect requiring `Env[ReadonlyVfs]`. */
    def run[A, S](vfs: ReadonlyVfs)(value: A < (Env[ReadonlyVfs] & S))(using Frame): A < S =
        Env.run(vfs)(value)

    /** Views a writable filesystem through the read-only API. */
    def from(vfs: Vfs): ReadonlyVfs =
        vfs

    /** Host-backed read-only filesystem constructors. */
    object host:

        /** Creates a host-backed VFS rooted at `root`.
          *
          * Operations are confined beneath `root` and exposed through virtual paths rooted at [[VPath.root]].
          */
        def init(root: Path)(using Frame): ReadonlyVfs < Sync =
            _root_.io.eleven19.kymora.vfs.internal.HostVfs.init(root).map(_.asReadonly)

    /** Mounted read-only filesystem constructors. */
    object mounted:

        /** Creates a read-only VFS by routing absolute mount points to backends. */
        def init(mounts: ReadonlyMount*)(using Frame): ReadonlyVfs < (Sync & Abort[VfsError]) =
            _root_.io.eleven19.kymora.vfs.internal.MountedVfs.initReadonly(mounts)
end ReadonlyVfs

/** Constructors, environment helpers, and streaming write support for [[Vfs]]. */
object Vfs:

    /** Retrieves the writable filesystem from the current Kyo environment. */
    def get(using Frame): Vfs < Env[Vfs] =
        Env.get[Vfs]

    /** Provides a writable filesystem to an effect requiring `Env[Vfs]`. */
    def run[A, S](vfs: Vfs)(value: A < (Env[Vfs] & S))(using Frame): A < S =
        Env.run(vfs)(value)

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
        def init(using Frame): Vfs < Sync =
            _root_.io.eleven19.kymora.vfs.internal.InMemoryVfs.init

    /** Host-backed filesystem constructors. */
    object host:

        /** Creates a writable host-backed VFS rooted at `root`.
          *
          * Operations are confined beneath `root` and exposed through virtual paths rooted at [[VPath.root]].
          */
        def init(root: Path)(using Frame): Vfs < Sync =
            _root_.io.eleven19.kymora.vfs.internal.HostVfs.init(root)

    /** Mounted filesystem constructors. */
    object mounted:

        /** Creates a writable VFS by routing absolute mount points to writable backends. */
        def init(mounts: Mount*)(using Frame): Vfs < (Sync & Abort[VfsError]) =
            _root_.io.eleven19.kymora.vfs.internal.MountedVfs.init(mounts)
end Vfs

/** A writable mount entry for [[Vfs.mounted.init]].
  *
  * Requests under absolute path `at` are translated into `root` inside `vfs`.
  */
final case class Mount(at: VPath, vfs: Vfs, root: VPath = VPath.root)

/** A read-only mount entry for [[ReadonlyVfs.mounted.init]].
  *
  * Requests under absolute path `at` are translated into `root` inside `vfs`.
  */
final case class ReadonlyMount(at: VPath, vfs: ReadonlyVfs, root: VPath = VPath.root)

/** Env-based syntax for using [[VPath]] values as filesystem operations. */
extension (path: VPath)

    /** Reads this path as UTF-8 text from `Env[ReadonlyVfs]`. */
    def read(using Frame): String < (Sync & Env[ReadonlyVfs] & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.read(path)
        yield value

    /** Reads this path as text from `Env[ReadonlyVfs]`. */
    def read(charset: Charset)(using Frame): String < (Sync & Env[ReadonlyVfs] & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.read(path, charset)
        yield value

    /** Reads this path as bytes from `Env[ReadonlyVfs]`. */
    def readBytes(using Frame): Span[Byte] < (Sync & Env[ReadonlyVfs] & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.readBytes(path)
        yield value

    /** Reads this path as UTF-8 lines from `Env[ReadonlyVfs]`. */
    def readLines(using Frame): Chunk[String] < (Sync & Env[ReadonlyVfs] & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.readLines(path)
        yield value

    /** Reads this path as lines from `Env[ReadonlyVfs]`. */
    def readLines(charset: Charset)(using Frame): Chunk[String] < (Sync & Env[ReadonlyVfs] & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.readLines(path, charset)
        yield value

    /** Returns metadata for this path from `Env[ReadonlyVfs]`. */
    def stat(using Frame): VfsStat < (Sync & Env[ReadonlyVfs] & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.stat(path)
        yield value

    /** Lists direct children of this path from `Env[ReadonlyVfs]`. */
    def list(using Frame): Chunk[VPath] < (Sync & Env[ReadonlyVfs] & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.list(path)
        yield value

    /** Lists direct children matching `glob` from `Env[ReadonlyVfs]`. */
    def list(glob: String)(using Frame): Chunk[VPath] < (Sync & Env[ReadonlyVfs] & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.list(path, glob)
        yield value

    /** Walks descendants of this path from `Env[ReadonlyVfs]`. */
    def walk(
        maxDepth: Int = Int.MaxValue,
        followLinks: Boolean = false
    )(using Frame): Stream[VPath, Sync & Scope & Env[ReadonlyVfs] & Abort[VfsError]] =
        Stream:
            for
                vfs <- ReadonlyVfs.get
                _   <- vfs.walk(path, maxDepth, followLinks).emit
            yield ()

    /** Reads the symlink target for this path from `Env[ReadonlyVfs]`. */
    def readSymlink(using Frame): VPath < (Sync & Env[ReadonlyVfs] & Abort[VfsError]) =
        for
            vfs   <- ReadonlyVfs.get
            value <- vfs.readSymlink(path)
        yield value

    /** Writes UTF-8 text to this path using `Env[Vfs]`. */
    def write(
        value: String,
        createFolders: Boolean = true
    )(using Frame): Unit < (Sync & Env[Vfs] & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.write(path, value, createFolders)
        yield ()

    /** Writes text to this path using `Env[Vfs]`. */
    def write(
        value: String,
        charset: Charset
    )(using Frame): Unit < (Sync & Env[Vfs] & Abort[VfsError]) =
        write(value, charset, createFolders = true)

    /** Writes text to this path using `Env[Vfs]`, optionally creating parents. */
    def write(
        value: String,
        charset: Charset,
        createFolders: Boolean
    )(using Frame): Unit < (Sync & Env[Vfs] & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.write(path, value, charset, createFolders)
        yield ()

    /** Writes bytes to this path using `Env[Vfs]`. */
    def writeBytes(
        value: Span[Byte],
        createFolders: Boolean = true
    )(using Frame): Unit < (Sync & Env[Vfs] & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.writeBytes(path, value, createFolders)
        yield ()

    /** Writes UTF-8 lines to this path using `Env[Vfs]`. */
    def writeLines(
        value: Chunk[String],
        createFolders: Boolean = true
    )(using Frame): Unit < (Sync & Env[Vfs] & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.writeLines(path, value, createFolders)
        yield ()

    /** Appends UTF-8 text to this path using `Env[Vfs]`. */
    def append(
        value: String,
        createFolders: Boolean = true
    )(using Frame): Unit < (Sync & Env[Vfs] & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.append(path, value, createFolders)
        yield ()

    /** Appends text to this path using `Env[Vfs]`. */
    def append(
        value: String,
        charset: Charset
    )(using Frame): Unit < (Sync & Env[Vfs] & Abort[VfsError]) =
        append(value, charset, createFolders = true)

    /** Appends text to this path using `Env[Vfs]`, optionally creating parents. */
    def append(
        value: String,
        charset: Charset,
        createFolders: Boolean
    )(using Frame): Unit < (Sync & Env[Vfs] & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.append(path, value, charset, createFolders)
        yield ()

    /** Appends bytes to this path using `Env[Vfs]`. */
    def appendBytes(
        value: Span[Byte],
        createFolders: Boolean = true
    )(using Frame): Unit < (Sync & Env[Vfs] & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.appendBytes(path, value, createFolders)
        yield ()

    /** Appends UTF-8 lines to this path using `Env[Vfs]`. */
    def appendLines(
        value: Chunk[String],
        createFolders: Boolean = true
    )(using Frame): Unit < (Sync & Env[Vfs] & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.appendLines(path, value, createFolders)
        yield ()

    /** Creates this path as a directory using `Env[Vfs]`. */
    def mkDir(using Frame): Unit < (Sync & Env[Vfs] & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.mkDir(path)
        yield ()

    /** Removes this path recursively using `Env[Vfs]`. */
    def removeAll(using Frame): Unit < (Sync & Env[Vfs] & Abort[VfsError]) =
        for
            vfs <- Vfs.get
            _   <- vfs.removeAll(path)
        yield ()
end extension
