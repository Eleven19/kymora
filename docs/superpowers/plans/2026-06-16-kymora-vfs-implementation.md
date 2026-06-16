# Kymora VFS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `kymora-vfs` module from the approved design with a test-first, realistic behavior suite covering virtual paths, read-only and writable capabilities, in-memory filesystems, root-confined host filesystems, and mounted filesystems.

**Architecture:** Implement small, focused Scala 3 files under `kymora/vfs/src/io/eleven19/kymora/vfs/`. Keep `ReadonlyVfs` and `Vfs` as public algebras, implement `InMemoryVfs`, `HostVfs`, and `MountedVfs` behind companion factories, and expose path-first syntax through extensions. Each production slice starts with a failing kyo-test suite, verifies the red failure, then adds the minimal implementation.

**Tech Stack:** Scala 3.8.4, Kyo `Sync`/`Abort`/`Env`/`Scope`/`Stream`, Kyo `Path` for host I/O, Mill, kyo-test, Jujutsu (`jj`) for version control.

---

## TDD Rules For This Plan

- Do not write production code for a behavior until its test has failed for the expected reason.
- Run the smallest relevant JVM test command after each red and green step.
- Keep tests behavior-focused and realistic: use file names, nested directories, symlinks, mounts, and host roots that resemble application use.
- After each task passes, run `./mill kymora.vfs.jvm.test`.
- Use `jj describe -m "..."` to describe the working-copy commit after coherent milestones. There is no staging area in this repo.

## File Structure

- Create `kymora/vfs/src/io/eleven19/kymora/vfs/VPath.scala`
  - Owns virtual path construction, parsing, normalization, `root`, `cwd`, `VPathContext`, and relative resolution.
- Create `kymora/vfs/src/io/eleven19/kymora/vfs/VfsMetadata.scala`
  - Owns `VfsSize`, `VfsTimestamp`, `VfsEntryType`, and `VfsStat`.
- Create `kymora/vfs/src/io/eleven19/kymora/vfs/VfsError.scala`
  - Owns the `VfsError` hierarchy and Kyo render support.
- Replace `kymora/vfs/src/io/eleven19/kymora/vfs/Vfs.scala`
  - Owns `ReadonlyVfs`, `Vfs`, companion helpers, `Mount`, `ReadonlyMount`, `WriteHandle`, and path-first extensions.
- Create `kymora/vfs/src/io/eleven19/kymora/vfs/internal/InMemoryVfs.scala`
  - Owns the fiber-safe mutable in-memory implementation.
- Create `kymora/vfs/src/io/eleven19/kymora/vfs/internal/HostVfs.scala`
  - Owns root-confined Kyo `Path` delegation.
- Create `kymora/vfs/src/io/eleven19/kymora/vfs/internal/MountedVfs.scala`
  - Owns longest-prefix mount routing and synthetic mount directories.
- Replace `kymora/vfs/test/src/io/eleven19/kymora/vfs/VfsTests.scala`
  - Remove the old `Vfs.name` scaffold test.
- Create focused test files:
  - `VPathTests.scala`
  - `VfsMetadataTests.scala`
  - `VfsErrorTests.scala`
  - `ReadonlyVfsApiTests.scala`
  - `InMemoryVfsTests.scala`
  - `HostVfsTests.scala`
  - `MountedVfsTests.scala`

## Task 1: Virtual Path Model

**Files:**
- Create: `kymora/vfs/src/io/eleven19/kymora/vfs/VPath.scala`
- Create: `kymora/vfs/src/io/eleven19/kymora/vfs/VfsError.scala`
- Create: `kymora/vfs/test/src/io/eleven19/kymora/vfs/VPathTests.scala`
- Modify: `kymora/vfs/test/src/io/eleven19/kymora/vfs/VfsTests.scala`

- [ ] **Step 1: Write failing VPath tests**

Create `VPathTests.scala` with these behavior tests:

```scala
package io.eleven19.kymora.vfs

import kyo.*
import kyo.test.*

class VPathTests extends Test[Any]:
    "constructs absolute and relative unix-style paths" in {
        assert((VPath.root / "etc" / "kymora.conf").show == "/etc/kymora.conf")
        assert((VPath.cwd / "src" / "main.scala").show == "src/main.scala")
    }

    "normalizes dot and dotdot without escaping root" in {
        assert(VPath.parse("/app/./config/../data").eval.show == "/app/data")
        assert(VPath.parse("/../../etc").eval.show == "/etc")
    }

    "preserves case sensitivity" in {
        assert(VPath.parse("/Readme.md").eval != VPath.parse("/README.md").eval)
    }

    "treats tilde as literal without context" in {
        assert(VPath.parse("~/config").eval.show == "~/config")
    }

    "expands tilde and cwd with context" in {
        val ctx = VPathContext(
            home = Maybe(VPath.root / "home" / "damian"),
            cwd = VPath.root / "workspace" / "kymora"
        )
        assert(VPath.parse("~/config", ctx).eval.show == "/home/damian/config")
        assert(VPath.parse("src/Vfs.scala", ctx).eval.show == "/workspace/kymora/src/Vfs.scala")
    }

    "rejects tilde expansion when context has no home" in {
        val result = Abort.run(VPath.parse("~/config", VPathContext())).eval
        assert(result.isFailure)
        assert(result.failure.exists(_.isInstanceOf[VfsError.NoHomeDirectory]))
    }

    "resolves relative paths against absolute base" in {
        val base = VPath.root / "workspace" / "kymora"
        assert((VPath.cwd / "docs" / ".." / "README.md").resolveAgainst(base).eval.show == "/workspace/kymora/README.md")
    }
end VPathTests
```

Remove the old `Vfs.name` scaffold assertion from `VfsTests.scala` so it does not require `Vfs.name`.

- [ ] **Step 2: Run test to verify RED**

Run:

```sh
./mill kymora.vfs.jvm.test.testOnly io.eleven19.kymora.vfs.VPathTests
```

Expected: compile failure with missing `VPath`, `VPathContext`, and `VfsError.NoHomeDirectory`.

- [ ] **Step 3: Implement minimal VPath**

Create a minimal `VfsError.scala` first so path parsing can fail through `Abort[VfsError]`:

```scala
package io.eleven19.kymora.vfs

import kyo.*

sealed abstract class VfsError(message: String, cause: String | Throwable = "")(using Frame)
    extends Exception(message, cause match
        case throwable: Throwable => throwable
        case _                    => null
    )

object VfsError:
    final case class InvalidPath(input: String, reason: String)(using Frame)
        extends VfsError(s"Invalid virtual path '$input': $reason")
    final case class NoHomeDirectory(input: String)(using Frame)
        extends VfsError(s"Cannot expand '$input' without a VPathContext home")
end VfsError
```

Create `VPath.scala` with:

```scala
package io.eleven19.kymora.vfs

import kyo.*

opaque type VPath = VPath.Data

object VPath:
    final case class Data(isAbsolute: Boolean, parts: Chunk[String]) derives CanEqual:
        def show: String =
            val body = parts.mkString("/")
            if isAbsolute then "/" + body else body

    given CanEqual[VPath, VPath] = CanEqual.derived

    val root: VPath = Data(isAbsolute = true, Chunk.empty)
    val cwd: VPath = Data(isAbsolute = false, Chunk.empty)

    type Part = String | VPath

    def apply(parts: Part*): VPath =
        normalize(isAbsolute = false, flatten(parts))

    def parse(input: String)(using Frame): VPath < Abort[VfsError] =
        Abort.get(Result.succeed(parseUnsafe(input, expandHome = Absent, cwd = Absent)))

    def parse(input: String, context: VPathContext)(using Frame): VPath < Abort[VfsError] =
        if input == "~" || input.startsWith("~/") then
            context.home match
                case Absent => Abort.fail(VfsError.NoHomeDirectory(input))
                case Present(home) =>
                    val suffix = input.stripPrefix("~").stripPrefix("/")
                    Abort.get(Result.succeed((home / suffix).resolveAgainst(context.cwd).eval))
        else
            Abort.get(Result.succeed(parseUnsafe(input, expandHome = Absent, cwd = Present(context.cwd))))

    infix def /(part: Part)(using Frame): VPath =
        root / part

    private def parseUnsafe(input: String, expandHome: Maybe[VPath], cwd: Maybe[VPath]): VPath =
        val absolute = input.startsWith("/")
        val parsed = normalize(absolute, Chunk.from(input.split("[/\\\\]", -1).toSeq))
        cwd match
            case Present(base) if !parsed.isAbsolute => parsed.resolveAgainst(base).eval
            case _                                   => parsed

    private def flatten(parts: Seq[Part]): Chunk[String] =
        Chunk.from(parts.flatMap {
            case s: String => s.split("[/\\\\]", -1).toSeq
            case p: VPath  => p.parts.toSeq
        })

    private def normalize(isAbsolute: Boolean, raw: Chunk[String]): VPath =
        val normalized = raw.foldLeft(Vector.empty[String]) { (acc, part) =>
            part match
                case "" | "." => acc
                case ".."     => if acc.nonEmpty then acc.dropRight(1) else acc
                case other    => acc :+ other
        }
        Data(isAbsolute, Chunk.from(normalized))

    extension (self: VPath)
        def parts: Chunk[String] = self.parts
        def show: String = self.show
        def isAbsolute: Boolean = self.isAbsolute
        def name: Maybe[String] = self.parts.lastMaybe
        def parent: Maybe[VPath] =
            if self.parts.isEmpty then Absent
            else Present(Data(self.isAbsolute, self.parts.init))
        infix def /(part: Part)(using Frame): VPath =
            normalize(self.isAbsolute, self.parts ++ flatten(Seq(part)))
        def resolveAgainst(base: VPath)(using Frame): VPath < Abort[VfsError] =
            if self.isAbsolute then self
            else if !base.isAbsolute then Abort.fail(VfsError.InvalidPath(base.show, "base must be absolute"))
            else Abort.get(Result.succeed(normalize(isAbsolute = true, base.parts ++ self.parts)))
        def relativeTo(prefix: VPath)(using Frame): VPath < Abort[VfsError] =
            if self.isAbsolute != prefix.isAbsolute || self.parts.take(prefix.parts.size) != prefix.parts then
                Abort.fail(VfsError.InvalidPath(self.show, s"not under ${prefix.show}"))
            else Abort.get(Result.succeed(Data(isAbsolute = false, self.parts.drop(prefix.parts.size))))
        def resolve(target: VPath)(using Frame): VPath =
            if target.isAbsolute then target else normalize(self.isAbsolute, self.parts ++ target.parts)
end VPath

final case class VPathContext(
    home: Maybe[VPath] = Absent,
    cwd: VPath = VPath.root
) derives CanEqual
```

If this minimal code exposes strict-equality or Kyo API mismatches, fix only those compile issues in this slice.

- [ ] **Step 4: Run test to verify GREEN**

Run:

```sh
./mill kymora.vfs.jvm.test.testOnly io.eleven19.kymora.vfs.VPathTests
```

Expected: `VPathTests` passes.

- [ ] **Step 5: Verify module and describe change**

Run:

```sh
./mill kymora.vfs.jvm.test
jj describe -m "Add virtual path model"
```

Expected: JVM tests pass; `jj status` shows the new path/test files in the working-copy commit.

## Task 2: Metadata And Errors

**Files:**
- Create: `kymora/vfs/src/io/eleven19/kymora/vfs/VfsMetadata.scala`
- Modify: `kymora/vfs/src/io/eleven19/kymora/vfs/VfsError.scala`
- Create: `kymora/vfs/test/src/io/eleven19/kymora/vfs/VfsMetadataTests.scala`
- Create: `kymora/vfs/test/src/io/eleven19/kymora/vfs/VfsErrorTests.scala`

- [ ] **Step 1: Write failing metadata/error tests**

Create `VfsMetadataTests.scala`:

```scala
package io.eleven19.kymora.vfs

import kyo.*
import kyo.test.*

class VfsMetadataTests extends Test[Any]:
    "renders byte sizes with useful units" in {
        assert(VfsSize.bytes(512).render == "512 B")
        assert(VfsSize.bytes(2048).render == "2 KB")
        assert(VfsSize.bytes(1536 * 1024).render == "1.5 MB")
    }

    "renders timestamps in UTC ISO-8601" in {
        assert(VfsTimestamp.epochMillis(1781602200000L).render == "2026-06-16T09:30:00Z")
    }

    "stores stat metadata using opaque domain types" in {
        val stat = VfsStat(VfsEntryType.File, VfsSize.bytes(42), VfsTimestamp.epochMillis(1000L))
        assert(stat.size.toBytes == 42L)
        assert(stat.lastModified.toEpochMillis == 1000L)
    }
end VfsMetadataTests
```

Create `VfsErrorTests.scala`:

```scala
package io.eleven19.kymora.vfs

import kyo.*
import kyo.test.*

class VfsErrorTests extends Test[Any]:
    "errors include path and operation context" in {
        val path = VPath.root / "config" / "app.conf"
        assert(VfsError.NotFound(path).getMessage.contains("/config/app.conf"))
        assert(VfsError.Unsupported(path, "createSymlink").getMessage.contains("createSymlink"))
    }

    "backend failures preserve cause" in {
        val cause = new IllegalStateException("boom")
        val error = VfsError.BackendFailure(VPath.root / "data.db", "read", cause)
        assert(error.getCause eq cause)
    }
end VfsErrorTests
```

- [ ] **Step 2: Run tests to verify RED**

Run:

```sh
./mill kymora.vfs.jvm.test.testOnly io.eleven19.kymora.vfs.VfsMetadataTests io.eleven19.kymora.vfs.VfsErrorTests
```

Expected: compile failure with missing `VfsSize`, `VfsTimestamp`, `VfsStat`, `VfsEntryType`, and the full `VfsError` cases.

- [ ] **Step 3: Implement metadata and errors**

Create `VfsMetadata.scala`:

```scala
package io.eleven19.kymora.vfs

import java.time.Instant
import kyo.*

opaque type VfsSize = Long

object VfsSize:
    given CanEqual[VfsSize, VfsSize] = CanEqual.derived
    def bytes(value: Long): VfsSize = value.max(0L)
    val zero: VfsSize = 0L

    extension (self: VfsSize)
        def toBytes: Long = self
        def render: String =
            if self < 1024 then s"$self B"
            else if self < 1024L * 1024L then format(self.toDouble / 1024d, "KB")
            else if self < 1024L * 1024L * 1024L then format(self.toDouble / (1024d * 1024d), "MB")
            else format(self.toDouble / (1024d * 1024d * 1024d), "GB")

    private def format(value: Double, unit: String): String =
        val rounded = BigDecimal(value).setScale(1, BigDecimal.RoundingMode.HALF_UP)
        if rounded.isWhole then s"${rounded.toLong} $unit" else s"$rounded $unit"

    given Render[VfsSize] with
        def asString(value: VfsSize): String = value.render

opaque type VfsTimestamp = Long

object VfsTimestamp:
    given CanEqual[VfsTimestamp, VfsTimestamp] = CanEqual.derived
    def epochMillis(value: Long): VfsTimestamp = value
    def now(using Frame): VfsTimestamp < Sync =
        Sync.Unsafe.defer(java.lang.System.currentTimeMillis())

    extension (self: VfsTimestamp)
        def toEpochMillis: Long = self
        def render: String = Instant.ofEpochMilli(self).toString

    given Render[VfsTimestamp] with
        def asString(value: VfsTimestamp): String = value.render

enum VfsEntryType derives CanEqual:
    case File, Directory, Symlink

final case class VfsStat(
    entryType: VfsEntryType,
    size: VfsSize,
    lastModified: VfsTimestamp
) derives CanEqual
```

Create `VfsError.scala` from the spec, extending `Exception` and adding `given Render[VfsError]`.

- [ ] **Step 4: Run tests to verify GREEN**

Run:

```sh
./mill kymora.vfs.jvm.test.testOnly io.eleven19.kymora.vfs.VfsMetadataTests io.eleven19.kymora.vfs.VfsErrorTests
```

Expected: both tests pass.

- [ ] **Step 5: Verify module and describe change**

Run:

```sh
./mill kymora.vfs.jvm.test
jj describe -m "Add VFS metadata and errors"
```

Expected: JVM tests pass.

## Task 3: Public API Algebras And Path-First Syntax

**Files:**
- Replace: `kymora/vfs/src/io/eleven19/kymora/vfs/Vfs.scala`
- Create: `kymora/vfs/test/src/io/eleven19/kymora/vfs/ReadonlyVfsApiTests.scala`

- [ ] **Step 1: Write failing API tests**

Create `ReadonlyVfsApiTests.scala` with a small fake implementation to test service helpers and capability boundaries:

```scala
package io.eleven19.kymora.vfs

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.test.*

class ReadonlyVfsApiTests extends Test[Any]:
    private val configPath = VPath.root / "config" / "app.conf"

    "ReadonlyVfs.run provides read-only path-first access" in {
        val fs = TestReadonlyVfs(Map(configPath.show -> "name=kymora"))
        val value = ReadonlyVfs.run(fs) {
            configPath.read
        }.eval
        assert(value == "name=kymora")
    }

    "Vfs.asReadonly explicitly downgrades writable capability" in {
        val fs = TestWritableVfs()
        val value = ReadonlyVfs.run(fs.asReadonly) {
            (VPath.root / "hello.txt").read
        }.eval
        assert(value == "hello")
    }

    "Vfs.run provides writable path-first access" in {
        val fs = TestWritableVfs()
        Vfs.run(fs) {
            (VPath.root / "out.txt").write("saved")
        }.eval
        assert(fs.writes == List("/out.txt" -> "saved"))
    }
end ReadonlyVfsApiTests
```

The fake classes should live in the same test file and implement only the methods the tests exercise, returning `VfsError.Unsupported` for unused methods.

- [ ] **Step 2: Run test to verify RED**

Run:

```sh
./mill kymora.vfs.jvm.test.testOnly io.eleven19.kymora.vfs.ReadonlyVfsApiTests
```

Expected: compile failure with missing `ReadonlyVfs`, new `Vfs` trait, `ReadonlyVfs.run`, `Vfs.run`, `asReadonly`, and path-first extensions.

- [ ] **Step 3: Implement API traits and helpers**

Replace `Vfs.scala` with:

- `trait ReadonlyVfs` signatures from the spec.
- `trait Vfs extends ReadonlyVfs` signatures from the spec plus default `def asReadonly = ReadonlyVfs.from(this)`.
- `object ReadonlyVfs` with `get`, `run`, and `from`.
- `object Vfs` with `get`, `run`, nested `WriteHandle`, and factory objects that call internal implementations once those exist.
- `final case class Mount` and `final case class ReadonlyMount`.
- Extension methods on `VPath`:
  - read-only: `read`, `readBytes`, `readLines`, `stat`, `list`, `walk`, `readSymlink`
  - writable: `write`, `writeBytes`, `writeLines`, `append`, `appendBytes`, `appendLines`, `mkDir`, `removeAll`

Use exact helper shapes:

```scala
object ReadonlyVfs:
    def get(using Frame): ReadonlyVfs < Env[ReadonlyVfs] =
        Env.get[ReadonlyVfs]

    def run[A, S](vfs: ReadonlyVfs)(value: A < (Env[ReadonlyVfs] & S))(using Frame): A < S =
        Env.run(vfs)(value)

    def from(vfs: Vfs): ReadonlyVfs = vfs

object Vfs:
    def get(using Frame): Vfs < Env[Vfs] =
        Env.get[Vfs]

    def run[A, S](vfs: Vfs)(value: A < (Env[Vfs] & S))(using Frame): A < S =
        Env.run(vfs)(value)
```

If `Env.run` type inference needs Kyo-specific `Reducible` evidence, follow the exact `Env.run` signature from Kyo and add the required using parameters rather than weakening the public API.

- [ ] **Step 4: Run test to verify GREEN**

Run:

```sh
./mill kymora.vfs.jvm.test.testOnly io.eleven19.kymora.vfs.ReadonlyVfsApiTests
```

Expected: API tests pass.

- [ ] **Step 5: Verify module and describe change**

Run:

```sh
./mill kymora.vfs.jvm.test
jj describe -m "Add VFS public API"
```

Expected: JVM tests pass.

## Task 4: In-Memory VFS Core Files And Directories

**Files:**
- Create: `kymora/vfs/src/io/eleven19/kymora/vfs/internal/InMemoryVfs.scala`
- Create: `kymora/vfs/test/src/io/eleven19/kymora/vfs/InMemoryVfsTests.scala`
- Modify: `kymora/vfs/src/io/eleven19/kymora/vfs/Vfs.scala`

- [ ] **Step 1: Write failing in-memory core tests**

Create `InMemoryVfsTests.scala` with realistic app file scenarios:

```scala
package io.eleven19.kymora.vfs

import kyo.*
import kyo.test.*

class InMemoryVfsTests extends Test[Any]:
    "writes, reads, and stats nested config files" in {
        val program =
            for
                fs <- Vfs.inMemory.init
                path = VPath.root / "etc" / "kymora" / "app.conf"
                _ <- fs.write(path, "port=8080")
                read <- fs.read(path)
                stat <- fs.stat(path)
            yield
                assert(read == "port=8080")
                assert(stat.entryType == VfsEntryType.File)
                assert(stat.size.toBytes == "port=8080".getBytes("UTF-8").length.toLong)
        program.eval
    }

    "lists direct children without recursively flattening" in {
        val program =
            for
                fs <- Vfs.inMemory.init
                _ <- fs.write(VPath.root / "var" / "log" / "app.log", "started")
                _ <- fs.write(VPath.root / "var" / "data" / "db.txt", "rows")
                children <- fs.list(VPath.root / "var")
            yield assert(children.map(_.show).toSet == Set("/var/log", "/var/data"))
        program.eval
    }

    "removes files and reports missing reads with NotFound" in {
        val program =
            for
                fs <- Vfs.inMemory.init
                path = VPath.root / "tmp" / "payload.txt"
                _ <- fs.write(path, "payload")
                removed <- fs.remove(path)
                result <- Abort.run(fs.read(path))
            yield
                assert(removed)
                assert(result.failure.exists(_.isInstanceOf[VfsError.NotFound]))
        program.eval
    }
end InMemoryVfsTests
```

- [ ] **Step 2: Run test to verify RED**

Run:

```sh
./mill kymora.vfs.jvm.test.testOnly io.eleven19.kymora.vfs.InMemoryVfsTests
```

Expected: compile failure or runtime unsupported failure because `Vfs.inMemory.init` is not implemented.

- [ ] **Step 3: Implement minimal in-memory tree**

Implement `internal.InMemoryVfs` with:

- an immutable tree model:
  - `Node.Directory(children: Map[String, Node], modified: VfsTimestamp)`
  - `Node.File(bytes: Chunk[Byte], modified: VfsTimestamp)`
  - `Node.Symlink(target: VPath, modified: VfsTimestamp)`
- a fiber-safe state holder:
  - use `AtomicRef[State]` for pure updates, or
  - use `Meter.initMutexUnscoped` around a mutable tree
- helpers:
  - `lookup(path, followLinks)`
  - `update(path)(f)`
  - `ensureParents(path, createFolders)`
  - `utf8` encode/decode

Expose:

```scala
object InMemoryVfs:
    def init(using Frame): Vfs < Sync =
        AtomicRef.init(State.empty).map(ref => new Impl(ref))
```

Wire `Vfs.inMemory.init` to `internal.InMemoryVfs.init`.

- [ ] **Step 4: Run test to verify GREEN**

Run:

```sh
./mill kymora.vfs.jvm.test.testOnly io.eleven19.kymora.vfs.InMemoryVfsTests
```

Expected: the three core in-memory tests pass.

- [ ] **Step 5: Verify module and describe change**

Run:

```sh
./mill kymora.vfs.jvm.test
jj describe -m "Add in-memory VFS core"
```

Expected: JVM tests pass.

## Task 5: In-Memory Complete Behavior

**Files:**
- Modify: `kymora/vfs/src/io/eleven19/kymora/vfs/internal/InMemoryVfs.scala`
- Modify: `kymora/vfs/test/src/io/eleven19/kymora/vfs/InMemoryVfsTests.scala`

- [ ] **Step 1: Add failing tests for lines, streams, symlinks, walk, move, copy, and concurrency**

Append tests:

```scala
"writes and appends lines with stable newline behavior" in {
    val program =
        for
            fs <- Vfs.inMemory.init
            path = VPath.root / "logs" / "app.log"
            _ <- fs.writeLines(path, Chunk("started", "ready"))
            _ <- fs.appendLines(path, Chunk("stopped"))
            read <- fs.read(path)
            lines <- fs.readLines(path)
        yield
            assert(read == "started\nready\nstopped\n")
            assert(lines == Chunk("started", "ready", "stopped"))
    program.eval
}

"resolves relative symlinks and detects loops" in {
    val program =
        for
            fs <- Vfs.inMemory.init
            _ <- fs.write(VPath.root / "releases" / "v1" / "config.txt", "ok")
            _ <- fs.createSymlink(VPath.root / "current", VPath("releases", "v1"))
            read <- fs.read(VPath.root / "current" / "config.txt")
            _ <- fs.createSymlink(VPath.root / "loop-a", VPath.root / "loop-b")
            _ <- fs.createSymlink(VPath.root / "loop-b", VPath.root / "loop-a")
            loop <- Abort.run(fs.realPath(VPath.root / "loop-a"))
        yield
            assert(read == "ok")
            assert(loop.failure.exists(_.isInstanceOf[VfsError.SymlinkLoop]))
    program.eval
}

"walk returns entries up to max depth" in {
    val program =
        for
            fs <- Vfs.inMemory.init
            _ <- fs.write(VPath.root / "a" / "b" / "c.txt", "c")
            _ <- fs.write(VPath.root / "a" / "d.txt", "d")
            walked <- Scope.run(fs.walk(VPath.root / "a", maxDepth = 1).run).map(_.map(_.show).toSet)
        yield assert(walked == Set("/a/b", "/a/d.txt"))
    program.eval
}

"moves and copies files within the same filesystem" in {
    val program =
        for
            fs <- Vfs.inMemory.init
            _ <- fs.write(VPath.root / "incoming" / "payload.txt", "payload")
            _ <- fs.copy(VPath.root / "incoming" / "payload.txt", VPath.root / "archive" / "payload.txt")
            _ <- fs.move(VPath.root / "incoming" / "payload.txt", VPath.root / "processed" / "payload.txt")
            archived <- fs.read(VPath.root / "archive" / "payload.txt")
            processed <- fs.read(VPath.root / "processed" / "payload.txt")
            missing <- Abort.run(fs.read(VPath.root / "incoming" / "payload.txt"))
        yield
            assert(archived == "payload")
            assert(processed == "payload")
            assert(missing.failure.exists(_.isInstanceOf[VfsError.NotFound]))
    program.eval
}
```

- [ ] **Step 2: Run tests to verify RED**

Run:

```sh
./mill kymora.vfs.jvm.test.testOnly io.eleven19.kymora.vfs.InMemoryVfsTests
```

Expected: failures in unimplemented line, symlink, walk, move, or copy behavior.

- [ ] **Step 3: Implement the missing in-memory behavior**

Add the minimal behavior to `InMemoryVfs.scala`:

- `writeLines`/`appendLines` join each line with `\n` and include the final newline.
- `readLines` splits UTF-8 content with final empty line dropped.
- `realPath` follows symlinks with a visited set.
- `readSymlink` returns the stored target for a symlink node.
- `walk` emits sorted deterministic child paths depth-first.
- `move` removes from source then writes at destination atomically under the same state update.
- `copy` deep-copies the source node.
- `removeAll` recursively removes files/directories/symlinks.

- [ ] **Step 4: Run tests to verify GREEN**

Run:

```sh
./mill kymora.vfs.jvm.test.testOnly io.eleven19.kymora.vfs.InMemoryVfsTests
```

Expected: all in-memory tests pass.

- [ ] **Step 5: Verify module and describe change**

Run:

```sh
./mill kymora.vfs.jvm.test
jj describe -m "Complete in-memory VFS behavior"
```

Expected: JVM tests pass.

## Task 6: Host VFS

**Files:**
- Create: `kymora/vfs/src/io/eleven19/kymora/vfs/internal/HostVfs.scala`
- Create: `kymora/vfs/test/src/io/eleven19/kymora/vfs/HostVfsTests.scala`
- Modify: `kymora/vfs/src/io/eleven19/kymora/vfs/Vfs.scala`

- [ ] **Step 1: Write failing host tests**

Create `HostVfsTests.scala`:

```scala
package io.eleven19.kymora.vfs

import kyo.*
import kyo.test.*

class HostVfsTests extends Test[Any]:
    "reads and writes under a confined host root" in {
        val program =
            for
                root <- Path.temp("kymora-vfs-host")
                fs <- Vfs.host.init(root)
                path = VPath.root / "config" / "app.conf"
                _ <- fs.write(path, "host=true")
                read <- fs.read(path)
                hostRead <- (root / "config" / "app.conf").read
            yield
                assert(read == "host=true")
                assert(hostRead == "host=true")
        Scope.run(program).eval
    }

    "rejects paths that try to escape the host root" in {
        val program =
            for
                root <- Path.temp("kymora-vfs-host")
                fs <- Vfs.host.init(root)
                result <- Abort.run(fs.write(VPath.parse("/../../outside.txt").eval, "nope"))
            yield assert(result.failure.exists(_.isInstanceOf[VfsError.AccessDenied]))
        Scope.run(program).eval
    }

    "reports existing host files through virtual stat and list" in {
        val program =
            for
                root <- Path.temp("kymora-vfs-host")
                _ <- (root / "data" / "users.txt").write("alice\nbob\n")
                fs <- Vfs.host.init(root)
                lines <- fs.readLines(VPath.root / "data" / "users.txt")
                children <- fs.list(VPath.root / "data")
            yield
                assert(lines == Chunk("alice", "bob"))
                assert(children.map(_.show).toSet == Set("/data/users.txt"))
        Scope.run(program).eval
    }
end HostVfsTests
```

- [ ] **Step 2: Run test to verify RED**

Run:

```sh
./mill kymora.vfs.jvm.test.testOnly io.eleven19.kymora.vfs.HostVfsTests
```

Expected: compile failure or unsupported factory failure for `Vfs.host.init`.

- [ ] **Step 3: Implement root-confined host delegation**

Implement `HostVfs.scala`:

- Store `root: kyo.Path`.
- Convert `VPath` to a host path by resolving virtual paths under `root`.
- Reject absolute/relative escapes with `VfsError.AccessDenied`.
- Use `root.realPath` and target `realPath` for existing paths before reads/list/walk when possible.
- Translate Kyo `FileException` values into `VfsError`.
- Delegate read/write/line/list/stat/move/copy/remove to Kyo `Path`.
- Return `VfsError.Unsupported(path, "createSymlink")` and `Unsupported(path, "readSymlink")` for host symlink creation/read-link in v1 if no public Kyo API exists.

Wire `Vfs.host.init` and `ReadonlyVfs.host.init`.

- [ ] **Step 4: Run test to verify GREEN**

Run:

```sh
./mill kymora.vfs.jvm.test.testOnly io.eleven19.kymora.vfs.HostVfsTests
```

Expected: host tests pass.

- [ ] **Step 5: Verify module and describe change**

Run:

```sh
./mill kymora.vfs.jvm.test
jj describe -m "Add confined host VFS"
```

Expected: JVM tests pass.

## Task 7: Mounted VFS

**Files:**
- Create: `kymora/vfs/src/io/eleven19/kymora/vfs/internal/MountedVfs.scala`
- Create: `kymora/vfs/test/src/io/eleven19/kymora/vfs/MountedVfsTests.scala`
- Modify: `kymora/vfs/src/io/eleven19/kymora/vfs/Vfs.scala`

- [ ] **Step 1: Write failing mounted tests**

Create `MountedVfsTests.scala`:

```scala
package io.eleven19.kymora.vfs

import kyo.*
import kyo.test.*

class MountedVfsTests extends Test[Any]:
    "routes by longest prefix match" in {
        val program =
            for
                app <- Vfs.inMemory.init
                data <- Vfs.inMemory.init
                _ <- app.write(VPath.root / "config.json", """{"name":"app"}""")
                _ <- data.write(VPath.root / "users.db", "users")
                fs <- Vfs.mounted.init(
                    Mount(VPath.root / "app", app),
                    Mount(VPath.root / "app" / "data", data)
                )
                config <- fs.read(VPath.root / "app" / "config.json")
                users <- fs.read(VPath.root / "app" / "data" / "users.db")
            yield
                assert(config.contains("app"))
                assert(users == "users")
        program.eval
    }

    "synthesizes mount directories when listing root" in {
        val program =
            for
                app <- Vfs.inMemory.init
                cache <- Vfs.inMemory.init
                fs <- Vfs.mounted.init(
                    Mount(VPath.root / "app", app),
                    Mount(VPath.root / "var" / "cache", cache)
                )
                rootChildren <- fs.list(VPath.root)
                varChildren <- fs.list(VPath.root / "var")
            yield
                assert(rootChildren.map(_.show).toSet == Set("/app", "/var"))
                assert(varChildren.map(_.show).toSet == Set("/var/cache"))
        program.eval
    }

    "rejects duplicate and relative mount points" in {
        val program =
            for
                mem <- Vfs.inMemory.init
                duplicate <- Abort.run(Vfs.mounted.init(Mount(VPath.root / "app", mem), Mount(VPath.root / "app", mem)))
                relative <- Abort.run(Vfs.mounted.init(Mount(VPath.cwd / "app", mem)))
            yield
                assert(duplicate.failure.exists(_.isInstanceOf[VfsError.InvalidPath]))
                assert(relative.failure.exists(_.isInstanceOf[VfsError.InvalidPath]))
        program.eval
    }

    "does not move across mounts in v1" in {
        val program =
            for
                app <- Vfs.inMemory.init
                data <- Vfs.inMemory.init
                _ <- app.write(VPath.root / "config.json", "config")
                fs <- Vfs.mounted.init(Mount(VPath.root / "app", app), Mount(VPath.root / "data", data))
                moved <- Abort.run(fs.move(VPath.root / "app" / "config.json", VPath.root / "data" / "config.json"))
            yield assert(moved.failure.exists(_.isInstanceOf[VfsError.Unsupported]))
        program.eval
    }
end MountedVfsTests
```

- [ ] **Step 2: Run test to verify RED**

Run:

```sh
./mill kymora.vfs.jvm.test.testOnly io.eleven19.kymora.vfs.MountedVfsTests
```

Expected: compile failure or unsupported factory failure for `Vfs.mounted.init`.

- [ ] **Step 3: Implement mounted routing**

Implement `MountedVfs.scala`:

- Validate all mount points are absolute.
- Validate no duplicate `at` paths.
- Sort mounts by descending `at.parts.size` for longest-prefix match.
- Resolve virtual path to `(mount, translatedPath)` where translated path is `mount.root / suffix`.
- Delegate read/write/stat/walk/remove to the selected backend.
- For `list`, combine delegated backend children with synthetic mount children below the listed path.
- For `move`/`copy`, delegate only when source and target resolve to the same mount; otherwise fail with `VfsError.Unsupported`.
- Implement read-only mounted router separately or via a shared generic router over `ReadonlyVfs`.

Wire `Vfs.mounted.init` and `ReadonlyVfs.mounted.init`.

- [ ] **Step 4: Run test to verify GREEN**

Run:

```sh
./mill kymora.vfs.jvm.test.testOnly io.eleven19.kymora.vfs.MountedVfsTests
```

Expected: mounted tests pass.

- [ ] **Step 5: Verify module and describe change**

Run:

```sh
./mill kymora.vfs.jvm.test
jj describe -m "Add mounted VFS routing"
```

Expected: JVM tests pass.

## Task 8: Cross-Platform Compile And Final Review

**Files:**
- Modify only files needed to fix compile/test issues discovered by verification.
- Review: `docs/superpowers/specs/2026-06-16-kymora-vfs-design.md`

- [ ] **Step 1: Run full VFS JVM verification**

Run:

```sh
./mill kymora.vfs.jvm.compile
./mill kymora.vfs.jvm.test
```

Expected: compile and tests pass with no warnings.

- [ ] **Step 2: Run broader compile for cross-platform API issues**

Run:

```sh
./mill kymora.vfs.js.compile
./mill kymora.vfs.native.compile
```

Expected: JS and Native compile. If host tests or `Path.temp` are JVM-only, keep those tests guarded by platform module placement or avoid platform-specific APIs in shared tests.

- [ ] **Step 3: Check formatting**

Run:

```sh
./mill kymora.vfs.jvm.checkFormat
```

Expected: formatting passes. If it fails, run:

```sh
./mill kymora.vfs.jvm.reformat
./mill kymora.vfs.jvm.checkFormat
```

- [ ] **Step 4: Review spec coverage**

Confirm every approved spec area has tests:

- `VPath.root`, `VPath.cwd`, context home expansion, and relative resolution.
- `ReadonlyVfs` explicit read-only provisioning and `Vfs.asReadonly`.
- `VfsSize` and `VfsTimestamp`.
- `VfsError` typed failures.
- In-memory files, directories, lines, streams, symlinks, walk, move/copy/remove.
- Host root confinement and Kyo `Path` delegation.
- Mounted longest-prefix routing, synthetic directories, duplicate validation, and cross-mount unsupported move/copy.
- Overlay documented as deferred and not implemented in v1.

- [ ] **Step 5: Final describe and status**

Run:

```sh
jj describe -m "Implement kymora-vfs"
jj status
```

Expected: working-copy commit is described and contains only VFS implementation, tests, and docs.

## Realistic Test Case Checklist

Use these as additional cases if any implementation edge looks weak during execution:

- A config file under `/etc/kymora/app.conf` read through `ReadonlyVfs`.
- A data file under `/var/lib/kymora/users.db` routed through a mounted host VFS.
- A cache file under `/var/cache/kymora/index.bin` routed through in-memory VFS.
- A symlink `/current -> releases/v1` resolving `/current/config.json`.
- A symlink loop `/a -> /b`, `/b -> /a`.
- A relative parse from `VPathContext(cwd = /workspace/project)`.
- A tilde parse from `VPathContext(home = /home/damian)`.
- A mounted root with `/app` and `/app/data` proving longest-prefix wins.
- Cross-mount move/copy explicitly failing with `Unsupported`.

## Plan Self-Review

- Spec coverage: every v1 feature from `2026-06-16-kymora-vfs-design.md` maps to a task above. Overlay remains deferred by design.
- Placeholder scan: no task depends on unstated behavior; each red step names exact tests and expected failures.
- Type consistency: public names match the spec: `ReadonlyVfs`, `Vfs`, `VPath`, `VPathContext`, `VfsSize`, `VfsTimestamp`, `VfsError`, `Mount`, and `ReadonlyMount`.
