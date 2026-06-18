package io.eleven19.kymora.vfs

import kyo.*
import kyo.test.*

class VfsDirectoryApiTests extends Test[Any]:

    "mkDir creates one explicit directory through backend and path syntax" in {
        val dir    = VPath.root / "var"
        val nested = dir / "nested"
        for
            fs <- Vfs.inMemory.init
            _  <- fs.mkDir(dir)
            _ <- Vfs.run(fs) {
                nested.mkDir
            }
            dirStat    <- fs.stat(dir)
            nestedStat <- fs.stat(nested)
        yield
            assert(dirStat.entryType == VfsEntryType.Directory)
            assert(nestedStat.entryType == VfsEntryType.Directory)
    }

    "mkDirs creates nested directories and is idempotent on in-memory backends" in {
        val nested = VPath.root / "opt" / "kymora" / "cache"
        for
            fs   <- Vfs.inMemory.init
            _    <- fs.mkDirs(nested)
            _    <- fs.mkDirs(nested)
            stat <- fs.stat(nested)
        yield assert(stat.entryType == VfsEntryType.Directory)
    }

    "mkDirs creates nested directories on host backends" in
        Scope.run:
            for
                td <- Vfs.host.tempDir(prefix = "kymora-vfs-mkdirs-")
                dir = td.root / "a" / "b" / "c"
                _    <- td.vfs.mkDirs(dir)
                stat <- td.vfs.stat(dir)
            yield assert(stat.entryType == VfsEntryType.Directory)

    "mkDirs fails with NotDirectory when a parent segment is a file" in {
        val file  = VPath.root / "file"
        val child = file / "child"
        for
            fs     <- Vfs.inMemory.init
            _      <- fs.write(file, "contents")
            result <- Abort.run(fs.mkDirs(child))
        yield result match
            case Result.Failure(VfsError.NotDirectory(path)) =>
                assert(path == file)
            case other =>
                fail(s"Expected NotDirectory($file), got $other")
    }

    "path.mkDirs works through Vfs.run" in {
        val nested = VPath.root / "build" / "classes"
        for
            fs <- Vfs.inMemory.init
            _ <- Vfs.run(fs) {
                nested.mkDirs
            }
            stat <- fs.stat(nested)
        yield assert(stat.entryType == VfsEntryType.Directory)
    }

    "Vfs.tempDir creates scoped scratch space in the active VFS and removes it on Scope exit" in {
        for
            fs <- Vfs.inMemory.init
            dir <- Scope.run {
                Vfs.run(fs) {
                    for
                        temp <- Vfs.tempDir(prefix = "example-")
                        _    <- (temp / "scratch.txt").write("payload")
                        read <- (temp / "scratch.txt").read
                    yield
                        assert(temp.show.startsWith("/tmp/example-"))
                        assert(read == "payload")
                        temp
                }
            }
            existsAfter <- fs.exists(dir)
        yield assert(!existsAfter)
    }

    "path.tempDir creates scoped scratch space under the selected parent" in {
        val parent = VPath.root / "work"
        for
            fs <- Vfs.inMemory.init
            dir <- Scope.run {
                Vfs.run(fs) {
                    for
                        _    <- parent.mkDirs
                        temp <- parent.tempDir(prefix = "scratch-")
                        _    <- (temp / "out.txt").write("ok")
                        read <- (temp / "out.txt").read
                    yield
                        assert(temp.show.startsWith("/work/scratch-"))
                        assert(read == "ok")
                        temp
                }
            }
            existsAfter <- fs.exists(dir)
        yield assert(!existsAfter)
    }

    "Vfs.host.tempDir returns a usable host-backed VFS rooted at virtual root" in
        Scope.run:
            for
                td <- Vfs.host.tempDir(prefix = "kymora-vfs-host-temp-")
                file = td.root / "notes" / "today.txt"
                _    <- td.vfs.write(file, "host temp")
                read <- td.vfs.read(file)
            yield
                assert(td.root == VPath.root)
                assert(read == "host temp")

    "mounted VFS tempDir works when the parent is inside a writable mount" in {
        val parent = VPath.root / "app" / "tmp"
        for
            backend <- Vfs.inMemory.init
            mounted <- Vfs.mounted.init(Mount(VPath.root / "app", backend))
            dir <- Scope.run {
                Vfs.run(mounted) {
                    for
                        temp <- Vfs.tempDir(parent = parent, prefix = "mounted-")
                        _    <- (temp / "payload.txt").write("mounted")
                        read <- (temp / "payload.txt").read
                    yield
                        assert(temp.show.startsWith("/app/tmp/mounted-"))
                        assert(read == "mounted")
                        temp
                }
            }
            existsAfter <- mounted.exists(dir)
        yield assert(!existsAfter)
    }

    "mounted VFS default tempDir fails when /tmp is not mounted" in {
        for
            backend <- Vfs.inMemory.init
            mounted <- Vfs.mounted.init(Mount(VPath.root / "app", backend))
            result <- Abort.run {
                Scope.run {
                    Vfs.run(mounted)(Vfs.tempDir())
                }
            }
        yield result match
            case Result.Failure(VfsError.NotFound(path)) =>
                assert(path == (VPath.root / "tmp"))
            case other =>
                fail(s"Expected NotFound(/tmp), got $other")
    }

end VfsDirectoryApiTests
