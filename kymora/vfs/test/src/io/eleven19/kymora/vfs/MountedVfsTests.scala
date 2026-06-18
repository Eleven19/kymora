package io.eleven19.kymora.vfs

import java.nio.charset.StandardCharsets

import kyo.*
import kyo.test.*

class MountedVfsTests extends Test[Any]:
    "routes reads and writes by longest matching mount prefix" in {
        val program =
            for
                appBackend  <- Vfs.inMemory.init
                dataBackend <- Vfs.inMemory.init
                mounted     <- Vfs.mounted.init(
                    Mount(VPath.root / "app", appBackend),
                    Mount(VPath.root / "app" / "data", dataBackend)
                )
                _           <- mounted.write(VPath.root / "app" / "config.txt", "app")
                _           <- mounted.write(VPath.root / "app" / "data" / "users.db", "data")
                appRead     <- appBackend.read(VPath.root / "config.txt")
                dataRead    <- dataBackend.read(VPath.root / "users.db")
                appMissing  <- Abort.run(appBackend.read(VPath.root / "data" / "users.db"))
            yield
                assert(appRead == "app")
                assert(dataRead == "data")
                assert(appMissing.failure.exists(_.isInstanceOf[VfsError.NotFound]))

        program
    }

    "lists synthetic root and intermediate mount directories deterministically" in {
        val program =
            for
                appBackend   <- Vfs.inMemory.init
                cacheBackend <- Vfs.inMemory.init
                _            <- appBackend.write(VPath.root / "config.txt", "app")
                _            <- cacheBackend.write(VPath.root / "entries" / "one.txt", "cache")
                mounted      <- Vfs.mounted.init(
                    Mount(VPath.root / "app", appBackend),
                    Mount(VPath.root / "var" / "cache", cacheBackend)
                )
                rootChildren <- mounted.list(VPath.root)
                varChildren  <- mounted.list(VPath.root / "var")
                appChildren  <- mounted.list(VPath.root / "app")
                varStat      <- mounted.stat(VPath.root / "var")
                varExists    <- mounted.exists(VPath.root / "var")
                varIsDir     <- mounted.isDirectory(VPath.root / "var")
            yield
                assert(rootChildren.map(_.show) == Chunk("/app", "/var"))
                assert(varChildren.map(_.show) == Chunk("/var/cache"))
                assert(appChildren.map(_.show) == Chunk("/app/config.txt"))
                assert(varStat.entryType == VfsEntryType.Directory)
                assert(varExists)
                assert(varIsDir)

        program
    }

    "combines backend and synthetic children with glob filtering" in {
        val program =
            for
                appBackend <- Vfs.inMemory.init
                logsBackend <- Vfs.inMemory.init
                _          <- appBackend.write(VPath.root / "config.txt", "app")
                _          <- appBackend.write(VPath.root / "data" / "from-app.txt", "app-data")
                mounted    <- Vfs.mounted.init(
                    Mount(VPath.root / "app", appBackend),
                    Mount(VPath.root / "app" / "data" / "logs", logsBackend)
                )
                children   <- mounted.list(VPath.root / "app")
                filtered   <- mounted.list(VPath.root / "app", "d*")
                badGlob    <- Abort.run(mounted.list(VPath.root / "app", "data/*"))
            yield
                assert(children.map(_.show) == Chunk("/app/config.txt", "/app/data"))
                assert(filtered.map(_.show) == Chunk("/app/data"))
                assert(badGlob.failure.exists(_.isInstanceOf[VfsError.Unsupported]))

        program
    }

    "walk includes synthetic and mounted children in mount-space order" in {
        val program =
            for
                appBackend   <- Vfs.inMemory.init
                cacheBackend <- Vfs.inMemory.init
                _            <- appBackend.write(VPath.root / "config.txt", "app")
                _            <- cacheBackend.write(VPath.root / "entry.txt", "cache")
                mounted      <- Vfs.mounted.init(
                    Mount(VPath.root / "app", appBackend),
                    Mount(VPath.root / "var" / "cache", cacheBackend)
                )
                walked       <- Scope.run(mounted.walk(VPath.root, maxDepth = 2).run)
            yield assert(walked.map(_.show) == Chunk("/app", "/app/config.txt", "/var", "/var/cache"))

        program
    }

    "walk follows backend symlinks while preserving requested mount-space root" in {
        val program =
            for
                backend <- Vfs.inMemory.init
                _       <- backend.write(VPath.root / "releases" / "v1" / "config.txt", "release=v1")
                _       <- backend.createSymlink(VPath.root / "current", VPath.root / "releases" / "v1")
                mounted <- Vfs.mounted.init(Mount(VPath.root / "app", backend))
                walked  <- Scope.run(mounted.walk(VPath.root / "app" / "current", followLinks = true).run)
            yield assert(walked.map(_.show) == Chunk("/app/current/config.txt"))

        program
    }

    "walk with followed links remains router-aware for nested mounts" in {
        val program =
            for
                appBackend  <- Vfs.inMemory.init
                logsBackend <- Vfs.inMemory.init
                _           <- appBackend.write(VPath.root / "config.txt", "app")
                _           <- appBackend.write(VPath.root / "releases" / "v1" / "config.txt", "release=v1")
                _           <- appBackend.createSymlink(VPath.root / "current", VPath.root / "releases" / "v1")
                _           <- logsBackend.write(VPath.root / "today.log", "entry")
                mounted     <- Vfs.mounted.init(
                    Mount(VPath.root / "app", appBackend),
                    Mount(VPath.root / "app" / "data" / "logs", logsBackend)
                )
                walked      <- Scope.run(mounted.walk(VPath.root / "app", followLinks = true).run)
            yield assert(walked.map(_.show) == Chunk(
                "/app/config.txt",
                "/app/current",
                "/app/current/config.txt",
                "/app/data",
                "/app/data/logs",
                "/app/data/logs/today.log",
                "/app/releases",
                "/app/releases/v1",
                "/app/releases/v1/config.txt"
            ))

        program
    }

    "walk propagates backend symlink loop errors in mount space" in {
        val program =
            for
                backend <- Vfs.inMemory.init
                _       <- backend.createSymlink(VPath.root / "loop", VPath.root / "loop")
                mounted <- Vfs.mounted.init(Mount(VPath.root / "app", backend))
                result  <- Abort.run(Scope.run(mounted.walk(VPath.root / "app" / "loop", followLinks = true).run))
            yield assert(result.failure.exists {
                case VfsError.SymlinkLoop(path) => path == (VPath.root / "app" / "loop")
                case _                          => false
            })

        program
    }

    "walk with followed links rejects symlink roots outside the mount root" in {
        val program =
            for
                backend <- Vfs.inMemory.init
                _       <- backend.write(VPath.root / "tenant-b" / "secret.txt", "secret")
                _       <- backend.createSymlink(VPath.root / "tenant-a" / "link", VPath.root / "tenant-b")
                _       <- backend.mkDir(VPath.root / "tenant-a" / "dir")
                _       <- backend.createSymlink(VPath.root / "tenant-a" / "dir" / "link", VPath.root / "tenant-b")
                mounted <- Vfs.mounted.init(Mount(VPath.root / "app", backend, root = VPath.root / "tenant-a"))
                root    <- Abort.run(Scope.run(mounted.walk(VPath.root / "app" / "link", followLinks = true).run))
                nested  <- Abort.run(Scope.run(mounted.walk(VPath.root / "app" / "dir" / "link", followLinks = true).run))
            yield
                assert(root.failure.exists(_.isInstanceOf[VfsError.AccessDenied]))
                assert(nested.failure.exists(_.isInstanceOf[VfsError.AccessDenied]))

        program
    }

    "synthetic mount parent directories shadow backend files" in {
        val program =
            for
                rootBackend  <- Vfs.inMemory.init
                cacheBackend <- Vfs.inMemory.init
                _            <- rootBackend.write(VPath.root / "var", "backend file")
                _            <- cacheBackend.write(VPath.root / "entry.txt", "cache")
                mounted      <- Vfs.mounted.init(
                    Mount(VPath.root, rootBackend),
                    Mount(VPath.root / "var" / "cache", cacheBackend)
                )
                exists       <- mounted.exists(VPath.root / "var")
                isDirectory  <- mounted.isDirectory(VPath.root / "var")
                stat         <- mounted.stat(VPath.root / "var")
                children     <- mounted.list(VPath.root / "var")
                walked       <- Scope.run(mounted.walk(VPath.root / "var").run)
                read         <- Abort.run(mounted.read(VPath.root / "var"))
                bytes        <- Abort.run(mounted.readBytes(VPath.root / "var"))
            yield
                assert(exists)
                assert(isDirectory)
                assert(stat.entryType == VfsEntryType.Directory)
                assert(children.map(_.show) == Chunk("/var/cache"))
                assert(walked.map(_.show) == Chunk("/var/cache", "/var/cache/entry.txt"))
                assert(read.failure.exists(_.isInstanceOf[VfsError.IsDirectory]))
                assert(bytes.failure.exists(_.isInstanceOf[VfsError.IsDirectory]))

        program
    }

    "synthetic mount parent directories cannot be mutated through writable APIs" in {
        val program =
            for
                rootBackend  <- Vfs.inMemory.init
                cacheBackend <- Vfs.inMemory.init
                _            <- rootBackend.write(VPath.root / "var", "backend file")
                mounted      <- Vfs.mounted.init(
                    Mount(VPath.root, rootBackend),
                    Mount(VPath.root / "var" / "cache", cacheBackend)
                )
                write        <- Abort.run(mounted.write(VPath.root / "var", "changed"))
                truncate     <- Abort.run(mounted.truncate(VPath.root / "var", VfsSize.zero))
                remove       <- Abort.run(mounted.remove(VPath.root / "var"))
                removeAll    <- Abort.run(mounted.removeAll(VPath.root / "var"))
                backendRead  <- rootBackend.read(VPath.root / "var")
                exists       <- mounted.exists(VPath.root / "var")
                isDirectory  <- mounted.isDirectory(VPath.root / "var")
            yield
                assert(write.failure.exists(_.isInstanceOf[VfsError.IsDirectory]))
                assert(truncate.failure.exists(_.isInstanceOf[VfsError.IsDirectory]))
                assert(remove.failure.exists(_.isInstanceOf[VfsError.IsDirectory]))
                assert(removeAll.failure.exists(_.isInstanceOf[VfsError.IsDirectory]))
                assert(backendRead == "backend file")
                assert(exists)
                assert(isDirectory)

        program
    }

    "synthetic mount parent directories shadow backend symlinks for realPath and readSymlink" in {
        val program =
            for
                rootBackend  <- Vfs.inMemory.init
                cacheBackend <- Vfs.inMemory.init
                _            <- rootBackend.mkDir(VPath.root / "target")
                _            <- rootBackend.createSymlink(VPath.root / "var", VPath.root / "target")
                mounted      <- Vfs.mounted.init(
                    Mount(VPath.root, rootBackend),
                    Mount(VPath.root / "var" / "cache", cacheBackend)
                )
                real         <- mounted.realPath(VPath.root / "var")
                link         <- Abort.run(mounted.readSymlink(VPath.root / "var"))
            yield
                assert(real == (VPath.root / "var"))
                assert(link.failure.exists {
                    case VfsError.IsDirectory(path) => path == (VPath.root / "var")
                    case _                          => false
                })

        program
    }

    "delegates same-mount copy and move while rejecting cross-mount copy and move" in {
        val program =
            for
                leftBackend <- Vfs.inMemory.init
                rightBackend <- Vfs.inMemory.init
                mounted     <- Vfs.mounted.init(
                    Mount(VPath.root / "left", leftBackend),
                    Mount(VPath.root / "right", rightBackend)
                )
                _           <- mounted.write(VPath.root / "left" / "source.txt", "payload")
                _           <- mounted.copy(VPath.root / "left" / "source.txt", VPath.root / "left" / "copy.txt")
                copied      <- leftBackend.read(VPath.root / "copy.txt")
                _           <- mounted.move(VPath.root / "left" / "copy.txt", VPath.root / "left" / "moved.txt")
                moved       <- leftBackend.read(VPath.root / "moved.txt")
                oldCopy     <- Abort.run(leftBackend.read(VPath.root / "copy.txt"))
                crossCopy   <- Abort.run(mounted.copy(VPath.root / "left" / "source.txt", VPath.root / "right" / "copy.txt"))
                crossMove   <- Abort.run(mounted.move(VPath.root / "left" / "source.txt", VPath.root / "right" / "moved.txt"))
            yield
                assert(copied == "payload")
                assert(moved == "payload")
                assert(oldCopy.failure.exists(_.isInstanceOf[VfsError.NotFound]))
                assert(crossCopy.failure.exists(_.isInstanceOf[VfsError.Unsupported]))
                assert(crossMove.failure.exists(_.isInstanceOf[VfsError.Unsupported]))

        program
    }

    "reports mount-space paths when rejecting moves into descendants" in {
        val program =
            for
                backend <- Vfs.inMemory.init
                mounted <- Vfs.mounted.init(Mount(VPath.root / "app", backend, root = VPath.root / "tenant-a"))
                _       <- mounted.write(VPath.root / "app" / "a" / "file.txt", "payload")
                result  <- Abort.run(mounted.move(VPath.root / "app" / "a", VPath.root / "app" / "a" / "b"))
            yield assert(result.failure.exists {
                case VfsError.InvalidPath(input, reason) =>
                    input == (VPath.root / "app" / "a" / "b").show &&
                        reason.contains((VPath.root / "app" / "a").show) &&
                        !input.contains("/tenant-a") &&
                        !reason.contains("/tenant-a")
                case _ =>
                    false
            })

        program
    }

    "readonly mounted VFS reads backend files and synthetic directories" in {
        val program =
            for
                appBackend <- Vfs.inMemory.init
                cacheBackend <- Vfs.inMemory.init
                _          <- appBackend.write(VPath.root / "config.txt", "app")
                mounted: ReadonlyVfs.Backend <- ReadonlyVfs.mounted.init(
                    ReadonlyMount(VPath.root / "app", appBackend.asReadonly),
                    ReadonlyMount(VPath.root / "var" / "cache", cacheBackend.asReadonly)
                )
                text       <- mounted.read(VPath.root / "app" / "config.txt")
                root       <- mounted.list(VPath.root)
                varStat    <- mounted.stat(VPath.root / "var")
            yield
                assert(text == "app")
                assert(root.map(_.show) == Chunk("/app", "/var"))
                assert(varStat.entryType == VfsEntryType.Directory)

        program
    }

    "validates duplicate and relative mount points" in {
        val program =
            for
                backend   <- Vfs.inMemory.init
                duplicate <- Abort.run(Vfs.mounted.init(
                    Mount(VPath.root / "app", backend),
                    Mount(VPath.root / "app", backend)
                ))
                relative  <- Abort.run(Vfs.mounted.init(Mount(VPath("app"), backend)))
                readonlyRelative <- Abort.run(ReadonlyVfs.mounted.init(ReadonlyMount(VPath("app"), backend.asReadonly)))
                relativeRoot     <- Abort.run(Vfs.mounted.init(Mount(VPath.root / "app", backend, root = VPath("tenant-a"))))
                readonlyRelativeRoot <- Abort.run(
                    ReadonlyVfs.mounted.init(
                        ReadonlyMount(VPath.root / "app", backend.asReadonly, root = VPath("tenant-a"))
                    )
                )
            yield
                assert(duplicate.failure.exists(_.isInstanceOf[VfsError.InvalidPath]))
                assert(relative.failure.exists(_.isInstanceOf[VfsError.InvalidPath]))
                assert(readonlyRelative.failure.exists(_.isInstanceOf[VfsError.InvalidPath]))
                assert(relativeRoot.failure.exists(_.isInstanceOf[VfsError.InvalidPath]))
                assert(readonlyRelativeRoot.failure.exists(_.isInstanceOf[VfsError.InvalidPath]))

        program
    }

    "reports NotFound when no mount matches an operation path" in {
        val program =
            for
                backend <- Vfs.inMemory.init
                mounted <- Vfs.mounted.init(Mount(VPath.root / "app", backend))
                read    <- Abort.run(mounted.read(VPath.root / "missing" / "file.txt"))
                write   <- Abort.run(mounted.write(VPath.root / "missing" / "file.txt", "payload"))
            yield
                assert(read.failure.exists {
                    case VfsError.NotFound(path) => path == (VPath.root / "missing" / "file.txt")
                    case _                       => false
                })
                assert(write.failure.exists {
                    case VfsError.NotFound(path) => path == (VPath.root / "missing" / "file.txt")
                    case _                       => false
                })

        program
    }

    "translates mount roots when delegating paths" in {
        val program =
            for
                backend <- Vfs.inMemory.init
                _       <- backend.write(VPath.root / "tenant-a" / "existing.txt", "existing")
                mounted <- Vfs.mounted.init(Mount(VPath.root / "app", backend, root = VPath.root / "tenant-a"))
                _       <- mounted.write(VPath.root / "app" / "config.txt", "config")
                written <- backend.read(VPath.root / "tenant-a" / "config.txt")
                read    <- mounted.read(VPath.root / "app" / "existing.txt")
            yield
                assert(written == "config")
                assert(read == "existing")

        program
    }

    "translates absolute symlink targets from backend root to mount space when reading links" in {
        val program =
            for
                backend <- Vfs.inMemory.init
                _       <- backend.createSymlink(
                    VPath.root / "tenant-a" / "current",
                    VPath.root / "tenant-a" / "releases" / "v1"
                )
                mounted <- Vfs.mounted.init(Mount(VPath.root / "app", backend, root = VPath.root / "tenant-a"))
                target  <- mounted.readSymlink(VPath.root / "app" / "current")
            yield assert(target == (VPath.root / "app" / "releases" / "v1"))

        program
    }

    "translates same-mount absolute symlink targets to backend space when creating links" in {
        val program =
            for
                backend <- Vfs.inMemory.init
                mounted <- Vfs.mounted.init(Mount(VPath.root / "app", backend, root = VPath.root / "tenant-a"))
                _       <- mounted.createSymlink(VPath.root / "app" / "current", VPath.root / "app" / "releases" / "v1")
                stored  <- backend.readSymlink(VPath.root / "tenant-a" / "current")
            yield assert(stored == (VPath.root / "tenant-a" / "releases" / "v1"))

        program
    }

    "rejects cross-mount absolute symlink targets and preserves relative targets" in {
        val program =
            for
                backend <- Vfs.inMemory.init
                mounted <- Vfs.mounted.init(Mount(VPath.root / "app", backend, root = VPath.root / "tenant-a"))
                rejected <- Abort.run(mounted.createSymlink(VPath.root / "app" / "bad", VPath.root / "other" / "target"))
                relative  = VPath.cwd / "releases" / "v1"
                _        <- mounted.createSymlink(VPath.root / "app" / "current", relative)
                stored   <- backend.readSymlink(VPath.root / "tenant-a" / "current")
            yield
                assert(rejected.failure.exists(_.isInstanceOf[VfsError.Unsupported]))
                assert(stored == relative)

        program
    }

    "does not leak absolute symlink targets outside the mount root" in {
        val program =
            for
                backend <- Vfs.inMemory.init
                _       <- backend.write(VPath.root / "tenant-b" / "secret.txt", "secret")
                _       <- backend.createSymlink(
                    VPath.root / "tenant-a" / "current",
                    VPath.root / "tenant-b" / "secret.txt"
                )
                mounted <- Vfs.mounted.init(Mount(VPath.root / "app", backend, root = VPath.root / "tenant-a"))
                link    <- Abort.run(mounted.readSymlink(VPath.root / "app" / "current"))
                real    <- Abort.run(mounted.realPath(VPath.root / "app" / "current"))
            yield
                assert(link.failure.exists(_.isInstanceOf[VfsError.AccessDenied]))
                assert(real.failure.exists(_.isInstanceOf[VfsError.AccessDenied]))

        program
    }

    "routes streaming reads and writes through mounted backends" in {
        val program =
            for
                backend <- Vfs.inMemory.init
                mounted <- Vfs.mounted.init(Mount(VPath.root / "app", backend))
                _       <- Scope.run {
                    for
                        handle <- mounted.writeStream(VPath.root / "app" / "stream.txt")
                        _      <- handle.writeString("one\n", StandardCharsets.UTF_8)
                        _      <- handle.writeString("two\n", StandardCharsets.UTF_8)
                    yield ()
                }
                lines   <- Scope.run(mounted.readLinesStream(VPath.root / "app" / "stream.txt").run)
                backendText <- backend.read(VPath.root / "stream.txt")
            yield
                assert(lines == Chunk("one", "two"))
                assert(backendText == "one\ntwo\n")

        program
    }
end MountedVfsTests
