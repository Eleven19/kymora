package io.eleven19.kymora.vfs

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

import kyo.*
import kyo.test.*

class ReadonlyVfsApiTests extends Test[Any]:
    private val configPath = VPath.root / "config" / "app.conf"

    "ReadonlyVfs.run provides read-only path-first access" in {
        val fs = TestReadonlyVfs(Map(configPath.show -> "name=kymora"))
        ReadonlyVfs.run(fs) {
            configPath.read
        }.map(value => assert(value == "name=kymora"))
    }

    "Vfs.asReadonly explicitly downgrades writable capability" in {
        val fs = TestWritableVfs()
        ReadonlyVfs.run(fs.asReadonly) {
            (VPath.root / "hello.txt").read
        }.map(value => assert(value == "hello"))
    }

    "Vfs.run provides writable path-first access" in {
        val fs = TestWritableVfs()
        Vfs.run(fs) {
            (VPath.root / "out.txt").write("saved")
        }.map(_ => assert(fs.writes == List("/out.txt" -> "saved")))
    }

    "PR example: Vfs.run handles a mixed read/write path-first program" in {
        val program =
            for
                _    <- (VPath.root / "notes.txt").write("hello")
                text <- (VPath.root / "notes.txt").read
            yield text

        for
            backend <- Vfs.inMemory.init
            text    <- Vfs.run(backend)(program)
        yield assert(text == "hello")
    }

    "PR example: ReadonlyVfs.run handles a read-only path-first program" in {
        val readOnly =
            (VPath.root / "config.json").read

        for
            backend <- Vfs.inMemory.init
            _       <- backend.write(VPath.root / "config.json", """{"name":"kymora"}""")
            text    <- ReadonlyVfs.run(backend.asReadonly)(readOnly)
        yield assert(text == """{"name":"kymora"}""")
    }

    private final case class TestReadonlyVfs(files: Map[String, String]) extends ReadonlyVfs.Backend:
        def exists(path: VPath): Boolean < Sync =
            Sync.defer(files.contains(path.show))

        def isDirectory(path: VPath): Boolean < Sync =
            Sync.defer(false)

        def isRegularFile(path: VPath): Boolean < Sync =
            Sync.defer(files.contains(path.show))

        def isSymbolicLink(path: VPath): Boolean < Sync =
            Sync.defer(false)

        def realPath(path: VPath): VPath < (Sync & Abort[VfsError]) =
            Sync.defer(path)

        def read(path: VPath): String < (Sync & Abort[VfsError]) =
            read(path, StandardCharsets.UTF_8)

        def read(path: VPath, charset: Charset): String < (Sync & Abort[VfsError]) =
            files.get(path.show) match
                case Some(value) => Sync.defer(value)
                case None        => Abort.fail(VfsError.NotFound(path))

        def readBytes(path: VPath): Span[Byte] < (Sync & Abort[VfsError]) =
            read(path).map(value => Span.from(value.getBytes(StandardCharsets.UTF_8)))

        def readLines(path: VPath): Chunk[String] < (Sync & Abort[VfsError]) =
            readLines(path, StandardCharsets.UTF_8)

        def readLines(path: VPath, charset: Charset): Chunk[String] < (Sync & Abort[VfsError]) =
            read(path, charset).map(value => Chunk.from(value.linesIterator.toSeq))

        def stat(path: VPath): VfsStat < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "stat"))

        def list(path: VPath): Chunk[VPath] < (Sync & Abort[VfsError]) =
            Sync.defer(Chunk.empty)

        def list(path: VPath, glob: String): Chunk[VPath] < (Sync & Abort[VfsError]) =
            Sync.defer(Chunk.empty)

        def walk(
            path: VPath,
            maxDepth: Int = Int.MaxValue,
            followLinks: Boolean = false
        ): Stream[VPath, Sync & Scope & Abort[VfsError]] =
            Stream.empty

        def readSymlink(path: VPath): VPath < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "readSymlink"))

        def readStream(path: VPath): Stream[String, Sync & Scope & Abort[VfsError]] =
            Stream.empty

        def readStream(path: VPath, charset: Charset): Stream[String, Sync & Scope & Abort[VfsError]] =
            Stream.empty

        def readBytesStream(path: VPath): Stream[Chunk[Byte], Sync & Scope & Abort[VfsError]] =
            Stream.empty

        def readLinesStream(path: VPath): Stream[String, Sync & Scope & Abort[VfsError]] =
            Stream.empty

        def readLinesStream(path: VPath, charset: Charset): Stream[String, Sync & Scope & Abort[VfsError]] =
            Stream.empty
    end TestReadonlyVfs

    private final class TestWritableVfs extends Vfs.Backend:
        private var recordedWrites: List[(String, String)] = Nil

        def writes: List[(String, String)] = recordedWrites.reverse

        override def asReadonly: ReadonlyVfs.Backend =
            ReadonlyVfs.from(this)

        def exists(path: VPath): Boolean < Sync =
            Sync.defer(path.show == "/hello.txt")

        def isDirectory(path: VPath): Boolean < Sync =
            Sync.defer(false)

        def isRegularFile(path: VPath): Boolean < Sync =
            exists(path)

        def isSymbolicLink(path: VPath): Boolean < Sync =
            Sync.defer(false)

        def realPath(path: VPath): VPath < (Sync & Abort[VfsError]) =
            Sync.defer(path)

        def read(path: VPath): String < (Sync & Abort[VfsError]) =
            read(path, StandardCharsets.UTF_8)

        def read(path: VPath, charset: Charset): String < (Sync & Abort[VfsError]) =
            if path.show == "/hello.txt" then Sync.defer("hello")
            else Abort.fail(VfsError.NotFound(path))

        def readBytes(path: VPath): Span[Byte] < (Sync & Abort[VfsError]) =
            read(path).map(value => Span.from(value.getBytes(StandardCharsets.UTF_8)))

        def readLines(path: VPath): Chunk[String] < (Sync & Abort[VfsError]) =
            readLines(path, StandardCharsets.UTF_8)

        def readLines(path: VPath, charset: Charset): Chunk[String] < (Sync & Abort[VfsError]) =
            read(path, charset).map(value => Chunk.from(value.linesIterator.toSeq))

        def stat(path: VPath): VfsStat < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "stat"))

        def list(path: VPath): Chunk[VPath] < (Sync & Abort[VfsError]) =
            Sync.defer(Chunk.empty)

        def list(path: VPath, glob: String): Chunk[VPath] < (Sync & Abort[VfsError]) =
            Sync.defer(Chunk.empty)

        def walk(
            path: VPath,
            maxDepth: Int = Int.MaxValue,
            followLinks: Boolean = false
        ): Stream[VPath, Sync & Scope & Abort[VfsError]] =
            Stream.empty

        def readSymlink(path: VPath): VPath < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "readSymlink"))

        def readStream(path: VPath): Stream[String, Sync & Scope & Abort[VfsError]] =
            Stream.empty

        def readStream(path: VPath, charset: Charset): Stream[String, Sync & Scope & Abort[VfsError]] =
            Stream.empty

        def readBytesStream(path: VPath): Stream[Chunk[Byte], Sync & Scope & Abort[VfsError]] =
            Stream.empty

        def readLinesStream(path: VPath): Stream[String, Sync & Scope & Abort[VfsError]] =
            Stream.empty

        def readLinesStream(path: VPath, charset: Charset): Stream[String, Sync & Scope & Abort[VfsError]] =
            Stream.empty

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
            Sync.defer {
                recordedWrites = (path.show -> value) :: recordedWrites
                ()
            }

        def writeBytes(
            path: VPath,
            value: Span[Byte],
            createFolders: Boolean = true
        ): Unit < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "writeBytes"))

        def writeLines(
            path: VPath,
            value: Chunk[String],
            createFolders: Boolean = true
        ): Unit < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "writeLines"))

        def append(
            path: VPath,
            value: String,
            createFolders: Boolean = true
        ): Unit < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "append"))

        override def append(
            path: VPath,
            value: String,
            charset: Charset
        ): Unit < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "append"))

        def append(
            path: VPath,
            value: String,
            charset: Charset,
            createFolders: Boolean
        ): Unit < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "append"))

        def appendBytes(
            path: VPath,
            value: Span[Byte],
            createFolders: Boolean = true
        ): Unit < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "appendBytes"))

        def appendLines(
            path: VPath,
            value: Chunk[String],
            createFolders: Boolean = true
        ): Unit < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "appendLines"))

        def truncate(path: VPath, size: VfsSize): Unit < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "truncate"))

        def setLastModified(
            path: VPath,
            timestamp: VfsTimestamp
        ): Unit < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "setLastModified"))

        def mkDir(path: VPath): Unit < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "mkDir"))

        def mkFile(path: VPath): Unit < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "mkFile"))

        def move(
            from: VPath,
            to: VPath,
            replaceExisting: Boolean = false
        ): Unit < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(from, "move"))

        def copy(
            from: VPath,
            to: VPath,
            replaceExisting: Boolean = false
        ): Unit < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(from, "copy"))

        def remove(path: VPath): Boolean < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "remove"))

        def removeExisting(path: VPath): Unit < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "removeExisting"))

        def removeAll(path: VPath): Unit < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "removeAll"))

        def createSymlink(path: VPath, target: VPath): Unit < (Sync & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "createSymlink"))

        def writeStream(
            path: VPath,
            append: Boolean = false,
            createFolders: Boolean = true
        ): Vfs.WriteHandle < (Sync & Scope & Abort[VfsError]) =
            Abort.fail(VfsError.Unsupported(path, "writeStream"))
    end TestWritableVfs
end ReadonlyVfsApiTests
