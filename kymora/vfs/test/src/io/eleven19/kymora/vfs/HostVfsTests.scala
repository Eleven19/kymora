package io.eleven19.kymora.vfs

import java.nio.charset.StandardCharsets

import io.eleven19.kymora.vfs.internal.HostPlatform
import kyo.*
import kyo.test.*

class HostVfsTests extends Test[Any]:
    "reads and writes under a confined host root" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                fs   <- Vfs.host.init(root)
                path  = VPath.root / "config" / "app.conf"
                _        <- fs.write(path, "host=true")
                read     <- fs.read(path)
                hostRead <- (root / "config" / "app.conf").read
            yield
                assert(read == "host=true")
                assert(hostRead == "host=true")

        Scope.run(program)
    }

    "honors explicit charsets when writing and appending host text" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                fs   <- Vfs.host.init(root)
                path = VPath.root / "encoded.txt"
                charset = StandardCharsets.UTF_16LE
                _        <- fs.write(path, "alpha", charset)
                _        <- fs.append(path, " beta", charset)
                rawBytes <- fs.readBytes(path)
                decoded  <- fs.read(path, charset)
            yield
                val expected = "alpha beta".getBytes(charset)
                assert(rawBytes.toArray.toSeq == expected.toSeq)
                assert(decoded == "alpha beta")

        Scope.run(program)
    }

    "keeps normalized virtual paths under the configured host root" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                fs   <- Vfs.host.init(root)
                path <- VPath.parse("/../../outside.txt")
                _             <- fs.write(path, "inside-root")
                confinedRead  <- (root / "outside.txt").read
                siblingExists <- (root / ".." / "outside.txt").exists
            yield
                assert(path.show == "/outside.txt")
                assert(confinedRead == "inside-root")
                assert(!siblingExists)

        Scope.run(program)
    }

    "allows host symlinks whose real target remains inside the root" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                real = root / "real"
                _ <- (real / "app.conf").write("safe=true")
                _ <- HostPlatform.createSymlink(root / "current", real)
                fs = Vfs.host.init(root)
                vfs     <- fs
                read    <- vfs.read(VPath.root / "current" / "app.conf")
                stat    <- vfs.stat(VPath.root / "current" / "app.conf")
                link    <- vfs.isSymbolicLink(VPath.root / "current")
                linkStat <- vfs.stat(VPath.root / "current")
                entries <- vfs.list(VPath.root / "current")
            yield
                assert(read == "safe=true")
                assert(stat.entryType == VfsEntryType.File)
                assert(link)
                assert(linkStat.entryType == VfsEntryType.Directory)
                assert(entries.map(_.show).toSet == Set("/current/app.conf"))

        Scope.run(program)
    }

    "preserves requested virtual root when walking safe host symlinks" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                real = root / "real"
                _ <- (real / "file.txt").write("payload")
                _ <- HostPlatform.createSymlink(root / "current", real)
                fs      <- Vfs.host.init(root)
                entries <- Scope.run(fs.walk(VPath.root / "current", followLinks = true).run)
            yield assert(entries.map(_.show) == Chunk("/current/file.txt"))

        Scope.run(program)
    }

    "rejects host symlinks whose real target escapes the root" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                outside <- Path.tempDir("kymora-vfs-host-outside")
                _       <- (outside / "secret.txt").write("secret=true")
                _       <- HostPlatform.createSymlink(root / "escape", outside)
                fs = Vfs.host.init(root)
                vfs        <- fs
                read       <- Abort.run(vfs.read(VPath.root / "escape" / "secret.txt"))
                stat       <- Abort.run(vfs.stat(VPath.root / "escape" / "secret.txt"))
                listed     <- Abort.run(vfs.list(VPath.root / "escape"))
                write      <- Abort.run(vfs.write(VPath.root / "escape" / "owned.txt", "nope"))
                _          <- Path(outside.toString).removeAll
            yield
                assert(read.failure.exists(_.isInstanceOf[VfsError.AccessDenied]))
                assert(stat.failure.exists(_.isInstanceOf[VfsError.AccessDenied]))
                assert(listed.failure.exists(_.isInstanceOf[VfsError.AccessDenied]))
                assert(write.failure.exists(_.isInstanceOf[VfsError.AccessDenied]))

        Scope.run(program)
    }

    "returns canonical virtual paths from realPath through safe host symlinks" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                real = root / "real"
                _ <- (real / "file.txt").write("payload")
                _ <- HostPlatform.createSymlink(root / "current", real)
                fs   <- Vfs.host.init(root)
                real <- fs.realPath(VPath.root / "current" / "file.txt")
            yield assert(real.show == "/real/file.txt")

        Scope.run(program)
    }

    "does not leak escaped host symlink existence through metadata probes" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                outside <- Path.tempDir("kymora-vfs-host-outside")
                _       <- (outside / "secret.txt").write("secret=true")
                _       <- HostPlatform.createSymlink(root / "escape", outside)
                fs         <- Vfs.host.init(root)
                exists     <- fs.exists(VPath.root / "escape" / "secret.txt")
                isDir      <- fs.isDirectory(VPath.root / "escape")
                isRegular  <- fs.isRegularFile(VPath.root / "escape" / "secret.txt")
                isLink     <- fs.isSymbolicLink(VPath.root / "escape")
                _          <- Path(outside.toString).removeAll
            yield
                assert(!exists)
                assert(!isDir)
                assert(!isRegular)
                assert(!isLink)

        Scope.run(program)
    }

    "fails walking through host symlinks whose children escape the root" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                outside <- Path.tempDir("kymora-vfs-host-outside")
                _       <- (outside / "secret.txt").write("secret=true")
                dir      = root / "dir"
                _       <- (dir / "inside.txt").write("inside=true")
                _       <- HostPlatform.createSymlink(dir / "escape", outside)
                fs     <- Vfs.host.init(root)
                result <- Abort.run(Scope.run(fs.walk(VPath.root / "dir", followLinks = true).run))
                _      <- Path(outside.toString).removeAll
            yield assert(result.failure.exists(_.isInstanceOf[VfsError.AccessDenied]))

        Scope.run(program)
    }

    "reports existing host files through virtual stat and list" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                _    <- (root / "data" / "users.txt").write("alice\nbob\n")
                fs   <- Vfs.host.init(root)
                path  = VPath.root / "data" / "users.txt"
                lines    <- fs.readLines(path)
                stat     <- fs.stat(path)
                children <- fs.list(VPath.root / "data")
            yield
                assert(lines == Chunk("alice", "bob"))
                assert(stat.entryType == VfsEntryType.File)
                assert(stat.size.toBytes == 10L)
                assert(children.map(_.show).toSet == Set("/data/users.txt"))

        Scope.run(program)
    }

    "reports stable host stat metadata without fabricating current timestamps" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                fs   <- Vfs.host.init(root)
                path  = VPath.root / "data" / "users.txt"
                _    <- fs.write(path, "alice\nbob\n")
                first <- fs.stat(path)
                second <- fs.stat(path)
            yield
                assert(first.size.toBytes == 10L)
                assert(first.lastModified == VfsTimestamp.epochMillis(0L))
                assert(second.lastModified == first.lastModified)

        Scope.run(program)
    }

    "recursively copies host directory trees" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                fs   <- Vfs.host.init(root)
                source = VPath.root / "source"
                target = VPath.root / "backup" / "source"
                _      <- fs.write(source / "conf" / "app.conf", "name=kymora")
                _      <- fs.write(source / "data" / "users.txt", "alice\nbob\n")
                _      <- fs.copy(source, target)
                conf   <- fs.read(target / "conf" / "app.conf")
                users  <- fs.read(target / "data" / "users.txt")
            yield
                assert(conf == "name=kymora")
                assert(users == "alice\nbob\n")

        Scope.run(program)
    }

    "replaces existing host directory trees when copy allows replacement" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                fs   <- Vfs.host.init(root)
                source = VPath.root / "source"
                target = VPath.root / "target"
                _      <- fs.write(source / "fresh.txt", "fresh")
                _      <- fs.write(target / "stale.txt", "stale")
                _      <- fs.copy(source, target, replaceExisting = true)
                fresh  <- fs.read(target / "fresh.txt")
                stale  <- Abort.run(fs.read(target / "stale.txt"))
            yield
                assert(fresh == "fresh")
                assert(stale.failure.exists(_.isInstanceOf[VfsError.NotFound]))

        Scope.run(program)
    }

    "does not delete host directories when copying them to themselves" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                fs   <- Vfs.host.init(root)
                dir   = VPath.root / "source"
                _    <- fs.write(dir / "file.txt", "payload")
                _    <- fs.copy(dir, dir, replaceExisting = true)
                read <- fs.read(dir / "file.txt")
            yield assert(read == "payload")

        Scope.run(program)
    }

    "rejects copying host directories into their own descendants" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                fs   <- Vfs.host.init(root)
                dir   = VPath.root / "source"
                target = dir / "child"
                _      <- fs.write(dir / "file.txt", "payload")
                result <- Abort.run(fs.copy(dir, target, replaceExisting = true))
                read   <- fs.read(dir / "file.txt")
            yield
                assert(result.failure.exists(_.isInstanceOf[VfsError.InvalidPath]))
                assert(read == "payload")

        Scope.run(program)
    }

    "reports NotDirectory at nearest host file parent when writing through a file" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                fs   <- Vfs.host.init(root)
                file  = VPath.root / "file"
                _      <- fs.write(file, "plain")
                result <- Abort.run(fs.write(file / "child", "bad"))
            yield result match
                case Result.Failure(VfsError.NotDirectory(path)) =>
                    assert(path == file)
                case other =>
                    fail(s"Expected NotDirectory($file), got $other")

        Scope.run(program)
    }

    "reports destination path when copy or move destination already exists" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                fs   <- Vfs.host.init(root)
                copySource = VPath.root / "copy-source.txt"
                copyDest   = VPath.root / "copy-dest.txt"
                moveSource = VPath.root / "move-source.txt"
                moveDest   = VPath.root / "move-dest.txt"
                _          <- fs.write(copySource, "source")
                _          <- fs.write(copyDest, "dest")
                _          <- fs.write(moveSource, "source")
                _          <- fs.write(moveDest, "dest")
                copyResult <- Abort.run(fs.copy(copySource, copyDest))
                moveResult <- Abort.run(fs.move(moveSource, moveDest))
            yield
                assert(copyResult.failure.exists {
                    case VfsError.AlreadyExists(path) => path == copyDest
                    case _                            => false
                })
                assert(moveResult.failure.exists {
                    case VfsError.AlreadyExists(path) => path == moveDest
                    case _                            => false
                })

        Scope.run(program)
    }

    "reports host symlink create and read-link as unsupported" in {
        val program =
            for
                root <- Path.tempDir("kymora-vfs-host")
                fs   <- Vfs.host.init(root)
                path  = VPath.root / "current"
                create <- Abort.run(fs.createSymlink(path, VPath.root / "releases" / "v1"))
                read   <- Abort.run(fs.readSymlink(path))
            yield
                assert(create.failure.exists(_.isInstanceOf[VfsError.Unsupported]))
                assert(read.failure.exists(_.isInstanceOf[VfsError.Unsupported]))

        Scope.run(program)
    }
end HostVfsTests
