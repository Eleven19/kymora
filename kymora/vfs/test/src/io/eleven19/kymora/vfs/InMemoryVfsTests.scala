package io.eleven19.kymora.vfs

import java.nio.charset.StandardCharsets

import kyo.*
import kyo.test.*

class InMemoryVfsTests extends Test[Any]:
    "writes, reads, and stats nested config files" in {
        val contents = "port=8080"
        val program =
            for
                fs   <- Vfs.inMemory.init
                path  = VPath.root / "etc" / "kymora" / "app.conf"
                _    <- fs.write(path, contents)
                read <- fs.read(path)
                stat <- fs.stat(path)
            yield
                assert(read == contents)
                assert(stat.entryType == VfsEntryType.File)
                assert(stat.size.toBytes == contents.getBytes(StandardCharsets.UTF_8).length.toLong)

        program
    }

    "lists direct children without recursively flattening" in {
        val program =
            for
                fs       <- Vfs.inMemory.init
                _        <- fs.write(VPath.root / "var" / "log" / "app.log", "started")
                _        <- fs.write(VPath.root / "var" / "data" / "db.txt", "rows")
                children <- fs.list(VPath.root / "var")
            yield assert(children.map(_.show).toSet == Set("/var/log", "/var/data"))

        program
    }

    "removes files and reports missing reads with NotFound" in {
        val program =
            for
                fs      <- Vfs.inMemory.init
                path     = VPath.root / "tmp" / "payload.txt"
                _       <- fs.write(path, "payload")
                removed <- fs.remove(path)
                result  <- Abort.run(fs.read(path))
            yield
                assert(removed)
                assert(result.failure.exists(_.isInstanceOf[VfsError.NotFound]))

        program
    }

    "does not overwrite directories when writing files" in {
        val program =
            for
                fs     <- Vfs.inMemory.init
                logDir  = VPath.root / "var" / "log"
                logFile = logDir / "app.log"
                _      <- fs.write(logFile, "started")
                result <- Abort.run(fs.write(logDir, "oops"))
                read   <- fs.read(logFile)
            yield
                assert(result.failure.exists(_.isInstanceOf[VfsError.IsDirectory]))
                assert(read == "started")

        program
    }

    "does not remove non-empty root without recursive removal" in {
        val program =
            for
                fs     <- Vfs.inMemory.init
                path    = VPath.root / "etc" / "app.conf"
                _      <- fs.write(path, "name=kymora")
                result <- Abort.run(fs.remove(VPath.root))
                read   <- fs.read(path)
            yield
                assert(result.failure.exists(_.isInstanceOf[VfsError.DirectoryNotEmpty]))
                assert(read == "name=kymora")

        program
    }

    "filters direct children with single-segment star globs" in {
        val program =
            for
                fs       <- Vfs.inMemory.init
                dir       = VPath.root / "var" / "log"
                _        <- fs.write(dir / "app.log", "started")
                _        <- fs.write(dir / "app.txt", "notes")
                children <- fs.list(dir, "*.log")
            yield assert(children.map(_.show).toSet == Set("/var/log/app.log"))

        program
    }

    "reports missing parent errors with absolute path context" in {
        val program =
            for
                fs     <- Vfs.inMemory.init
                path    = VPath.root / "etc" / "kymora" / "app.conf"
                result <- Abort.run(fs.write(path, "port=8080", createFolders = false))
            yield
                assert(result.failure.exists {
                    case VfsError.NotFound(errorPath) => errorPath == path
                    case _                            => false
                })

        program
    }

    "reports exact nested directory path when removing non-empty directories" in {
        val program =
            for
                fs     <- Vfs.inMemory.init
                dir     = VPath.root / "a" / "b"
                file    = dir / "file.txt"
                _      <- fs.write(file, "nested")
                result <- Abort.run(fs.remove(dir))
                read   <- fs.read(file)
            yield
                assert(result.failure.exists {
                    case VfsError.DirectoryNotEmpty(errorPath) => errorPath == dir
                    case _                                     => false
                })
                assert(read == "nested")

        program
    }

    "reports exact nested file path when making directories through files" in {
        val program =
            for
                fs            <- Vfs.inMemory.init
                file           = VPath.root / "a" / "file.txt"
                requestedChild = file / "child"
                _             <- fs.write(file, "contents")
                result        <- Abort.run(fs.mkDir(requestedChild))
                read          <- fs.read(file)
            yield
                assert(result.failure.exists {
                    case VfsError.NotDirectory(errorPath) => errorPath == file
                    case _                                => false
                })
                assert(read == "contents")

        program
    }

    "reports exact nested file path when writing through files" in {
        val program =
            for
                fs            <- Vfs.inMemory.init
                file           = VPath.root / "a" / "file.txt"
                requestedChild = file / "child"
                _             <- fs.write(file, "contents")
                result        <- Abort.run(fs.write(requestedChild, "bad"))
                read          <- fs.read(file)
            yield
                assert(result.failure.exists {
                    case VfsError.NotDirectory(errorPath) => errorPath == file
                    case _                                => false
                })
                assert(read == "contents")

        program
    }

    "writes, appends, and reads lines with stable newline behavior" in {
        val program =
            for
                fs    <- Vfs.inMemory.init
                path   = VPath.root / "etc" / "app.conf"
                _     <- fs.writeLines(path, Chunk("port=8080", "host=localhost"))
                first <- fs.read(path)
                _     <- fs.appendLines(path, Chunk("debug=true"))
                text  <- fs.read(path)
                lines <- fs.readLines(path)
            yield
                assert(first == "port=8080\nhost=localhost\n")
                assert(text == "port=8080\nhost=localhost\ndebug=true\n")
                assert(lines == Chunk("port=8080", "host=localhost", "debug=true"))

        program
    }

    "resolves relative symlinks for reads while reporting link metadata at link path" in {
        val program =
            for
                fs       <- Vfs.inMemory.init
                releases  = VPath.root / "releases" / "v1"
                current   = VPath.root / "current"
                target    = VPath.cwd / "releases" / "v1"
                _        <- fs.write(releases / "config.txt", "release=v1")
                _        <- fs.createSymlink(current, target)
                text     <- fs.read(current / "config.txt")
                stored   <- fs.readSymlink(current)
                isLink   <- fs.isSymbolicLink(current)
                stat     <- fs.stat(current)
            yield
                assert(text == "release=v1")
                assert(stored == target)
                assert(isLink)
                assert(stat.entryType == VfsEntryType.Symlink)

        program
    }

    "resolves relative symlinks that navigate to sibling directories" in {
        val program =
            for
                fs     <- Vfs.inMemory.init
                target <- VPath.parse("../releases/v1")
                _      <- fs.write(VPath.root / "releases" / "v1" / "config.txt", "release=v1")
                _      <- fs.createSymlink(VPath.root / "apps" / "current", target)
                text   <- fs.read(VPath.root / "apps" / "current" / "config.txt")
                real   <- fs.realPath(VPath.root / "apps" / "current" / "config.txt")
            yield
                assert(text == "release=v1")
                assert(real.show == "/releases/v1/config.txt")

        program
    }

    "detects symlink loops during normal reads" in {
        val program =
            for
                fs     <- Vfs.inMemory.init
                _      <- fs.createSymlink(VPath.root / "a", VPath.cwd / "b")
                _      <- fs.createSymlink(VPath.root / "b", VPath.cwd / "a")
                result <- Abort.run(fs.read(VPath.root / "a" / "file.txt"))
            yield assert(result.failure.exists(_.isInstanceOf[VfsError.SymlinkLoop]))

        program
    }

    "realPath follows relative symlinks to the resolved target path" in {
        val program =
            for
                fs       <- Vfs.inMemory.init
                releases  = VPath.root / "releases" / "v1"
                current   = VPath.root / "current"
                _        <- fs.write(releases / "config.txt", "release=v1")
                _        <- fs.createSymlink(current, VPath.cwd / "releases" / "v1")
                real     <- fs.realPath(current / "config.txt")
            yield assert(real.show == "/releases/v1/config.txt")

        program
    }

    "realPath detects symlink loops" in {
        val program =
            for
                fs     <- Vfs.inMemory.init
                _      <- fs.createSymlink(VPath.root / "loop-a", VPath.root / "loop-b")
                _      <- fs.createSymlink(VPath.root / "loop-b", VPath.root / "loop-a")
                result <- Abort.run(fs.realPath(VPath.root / "loop-a"))
            yield assert(result.failure.exists(_.isInstanceOf[VfsError.SymlinkLoop]))

        program
    }

    "walks direct children deterministically when maxDepth is one" in {
        val program =
            for
                fs       <- Vfs.inMemory.init
                root      = VPath.root / "var"
                _        <- fs.write(root / "zeta" / "last.txt", "z")
                _        <- fs.write(root / "alpha" / "first.txt", "a")
                _        <- fs.write(root / "middle.txt", "m")
                walked   <- Scope.run(fs.walk(root, maxDepth = 1).run)
            yield assert(walked.map(_.show) == Chunk("/var/alpha", "/var/middle.txt", "/var/zeta"))

        program
    }

    "walk follows the requested root when it is a symlink" in {
        val program =
            for
                fs     <- Vfs.inMemory.init
                _      <- fs.write(VPath.root / "real" / "child.txt", "child")
                _      <- fs.createSymlink(VPath.root / "link", VPath.root / "real")
                walked <- Scope.run(fs.walk(VPath.root / "link", followLinks = true).run)
            yield assert(walked.map(_.show) == Chunk("/link/child.txt"))

        program
    }

    "walk detects symlink loops when following links" in {
        val program =
            for
                fs     <- Vfs.inMemory.init
                _      <- fs.createSymlink(VPath.root / "loop", VPath.root / "loop")
                result <- Abort.run(Scope.run(fs.walk(VPath.root / "loop", followLinks = true).run))
            yield assert(result.failure.exists(_.isInstanceOf[VfsError.SymlinkLoop]))

        program
    }

    "copies, moves, and recursively removes directory trees" in {
        val program =
            for
                fs          <- Vfs.inMemory.init
                source       = VPath.root / "srv" / "app"
                copied       = VPath.root / "backup" / "app"
                moved        = VPath.root / "archive" / "app"
                _           <- fs.write(source / "conf" / "app.conf", "name=kymora")
                _           <- fs.write(source / "data" / "rows.txt", "1\n2\n")
                _           <- fs.copy(source, copied)
                copiedConf  <- fs.read(copied / "conf" / "app.conf")
                copiedRows  <- fs.read(copied / "data" / "rows.txt")
                _           <- fs.move(copied, moved)
                oldExists   <- fs.exists(copied)
                movedConf   <- fs.read(moved / "conf" / "app.conf")
                _           <- fs.removeAll(VPath.root / "srv")
                sourceGone  <- fs.exists(source)
            yield
                assert(copiedConf == "name=kymora")
                assert(copiedRows == "1\n2\n")
                assert(!oldExists)
                assert(movedConf == "name=kymora")
                assert(!sourceGone)

        program
    }

    "updates parent directory timestamp after removing a child" in {
        val program =
            for
                fs     <- Vfs.inMemory.init
                parent  = VPath.root / "tmp"
                child   = parent / "remove-me.txt"
                old     = VfsTimestamp.epochMillis(1L)
                _      <- fs.write(child, "payload")
                _      <- fs.setLastModified(parent, old)
                _      <- fs.removeExisting(child)
                stat   <- fs.stat(parent)
            yield assert(stat.lastModified != old)

        program
    }

    "updates source parent timestamp after moving a child out" in {
        val program =
            for
                fs     <- Vfs.inMemory.init
                source  = VPath.root / "from"
                child   = source / "move-me.txt"
                old     = VfsTimestamp.epochMillis(1L)
                _      <- fs.write(child, "payload")
                _      <- fs.mkDir(VPath.root / "to")
                _      <- fs.setLastModified(source, old)
                _      <- fs.move(child, VPath.root / "to" / "move-me.txt")
                stat   <- fs.stat(source)
            yield assert(stat.lastModified != old)

        program
    }

    "uses the mutation timestamp when recursively removing root" in {
        val program =
            for
                fs     <- Vfs.inMemory.init
                old     = VfsTimestamp.epochMillis(0L)
                _      <- fs.write(VPath.root / "tmp" / "payload.txt", "payload")
                _      <- fs.setLastModified(VPath.root, old)
                _      <- fs.removeAll(VPath.root)
                stat   <- fs.stat(VPath.root)
            yield assert(stat.lastModified != old)

        program
    }

    "moves a file to itself without losing data" in {
        val program =
            for
                fs     <- Vfs.inMemory.init
                path    = VPath.root / "tmp" / "same.txt"
                _      <- fs.write(path, "still here")
                _      <- fs.move(path, path)
                result <- fs.read(path)
            yield assert(result == "still here")

        program
    }

    "rejects moving a directory into its own descendant without losing source data" in {
        val program =
            for
                fs        <- Vfs.inMemory.init
                source     = VPath.root / "a"
                file       = source / "file.txt"
                _         <- fs.write(file, "keep me")
                result    <- Abort.run(fs.move(source, source / "b"))
                preserved <- Abort.run(fs.read(file))
            yield
                assert(result.failure.exists {
                    case VfsError.InvalidPath(_, _) | VfsError.Unsupported(_, _) => true
                    case _                                                       => false
                })
                assert(preserved.exists(_ == "keep me"))

        program
    }

    "handles concurrent writes without dropping in-memory updates" in {
        val program =
            for
                fs       <- Vfs.inMemory.init
                jobs      = VPath.root / "jobs"
                count     = 64
                _        <- Async.foreachDiscard(0 until count, concurrency = 16) { n =>
                    fs.write(jobs / s"$n.txt", s"job-$n")
                }
                children <- fs.list(jobs)
                contents <- Async.foreach(0 until count, concurrency = 16) { n =>
                    fs.read(jobs / s"$n.txt")
                }
            yield
                assert(children.map(_.show).toSet == (0 until count).map(n => s"/jobs/$n.txt").toSet)
                assert(contents.toSet == (0 until count).map(n => s"job-$n").toSet)

        program
    }

    "appends bytes and truncates files deterministically" in {
        val program =
            for
                fs       <- Vfs.inMemory.init
                textPath  = VPath.root / "logs" / "app.log"
                binPath   = VPath.root / "tmp" / "payload.bin"
                _        <- fs.append(textPath, "started\n")
                _        <- fs.append(textPath, "ready\n")
                text     <- fs.read(textPath)
                _        <- fs.writeBytes(binPath, Span.from(Array[Byte](1, 2, 3)))
                _        <- fs.appendBytes(binPath, Span.from(Array[Byte](4, 5)))
                appended <- fs.readBytes(binPath)
                _        <- fs.truncate(binPath, VfsSize.bytes(3))
                shrunk   <- fs.readBytes(binPath)
                _        <- fs.truncate(binPath, VfsSize.bytes(5))
                extended <- fs.readBytes(binPath)
            yield
                assert(text == "started\nready\n")
                assert(appended.toArray.toSeq == Seq[Byte](1, 2, 3, 4, 5))
                assert(shrunk.toArray.toSeq == Seq[Byte](1, 2, 3))
                assert(extended.toArray.toSeq == Seq[Byte](1, 2, 3, 0, 0))

        program
    }

    "updates last modified timestamps explicitly" in {
        val program =
            for
                fs   <- Vfs.inMemory.init
                path  = VPath.root / "tmp" / "stamp.txt"
                stamp = VfsTimestamp.epochMillis(123456789L)
                _    <- fs.write(path, "payload")
                _    <- fs.setLastModified(path, stamp)
                stat <- fs.stat(path)
            yield assert(stat.lastModified == stamp)

        program
    }

    "closes write stream handles when scope exits" in {
        var captured: Maybe[Vfs.WriteHandle] = Absent
        val program =
            for
                fs     <- Vfs.inMemory.init
                path    = VPath.root / "streams" / "closed.txt"
                _      <- Scope.run {
                    for
                        handle <- fs.writeStream(path)
                        _      <- Sync.defer { captured = Present(handle) }
                        _      <- handle.writeString("inside")
                    yield ()
                }
                result <- captured match
                    case Present(handle) =>
                        Abort.run(handle.writeString("outside"))
                    case Absent =>
                        Abort.run(Abort.fail(VfsError.Unsupported(path, "writeStream.missing")))
                text   <- fs.read(path)
            yield
                assert(result.failure.exists {
                    case VfsError.Unsupported(errorPath, operation) =>
                        errorPath == path && operation == "writeStream.closed"
                    case _ =>
                        false
                })
                assert(text == "inside")

        program
    }

    "reads and writes streams for in-memory files" in {
        val program =
            for
                fs    <- Vfs.inMemory.init
                path   = VPath.root / "streams" / "payload.txt"
                _     <- Scope.run {
                    for
                        handle <- fs.writeStream(path)
                        _      <- handle.writeString("alpha\n")
                        _      <- handle.writeString("beta\n")
                    yield ()
                }
                text  <- Scope.run(fs.readStream(path).run)
                bytes <- Scope.run(fs.readBytesStream(path).run)
                lines <- Scope.run(fs.readLinesStream(path).run)
            yield
                assert(text == Chunk("alpha\nbeta\n"))
                assert(bytes.map(_.toArray.toSeq) == Chunk(Seq[Byte](97, 108, 112, 104, 97, 10, 98, 101, 116, 97, 10)))
                assert(lines == Chunk("alpha", "beta"))

        program
    }
end InMemoryVfsTests
