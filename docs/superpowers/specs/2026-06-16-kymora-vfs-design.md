# Kymora VFS Design

Date: 2026-06-16

## Summary

The `kymora-vfs` module will provide a Kyo-native virtual filesystem API with
explicit read-only and writable capabilities. It will support deterministic
virtual paths, in-memory filesystems, root-confined host filesystems, and
Docker-style mounted filesystems in the first implementation pass. Overlay
filesystems are part of the design direction but deferred until their whiteout
and copy-up semantics can be specified carefully.

The design follows Kyo patterns:

- capabilities are ordinary values and can also be provided through `Env`
- factories use `init` naming
- streaming APIs use `Stream` plus `Scope`
- errors use typed `Abort[VfsError]`
- path-first ergonomics are available as extensions over the service API

## Goals

- Provide a cross-platform VFS API for Kyo applications.
- Let APIs advertise read-only filesystem access through `Env[ReadonlyVfs]`.
- Let mutating APIs require the stronger `Env[Vfs]` capability.
- Support in-memory, root-confined host, and mounted VFS implementations in v1.
- Keep virtual paths deterministic across JVM, JS, and Native.
- Include Kyo `Path`-like file, line, stream, directory, stat, walk, and symlink
  operations.
- Leave room for overlay filesystems without committing to subtle delete/list
  semantics in v1.

## Non-Goals

- Exposing an unconfined host filesystem backend in v1.
- Making virtual paths inherit host platform behavior such as drive letters or
  case-insensitive lookup.
- Automatically expanding `~` during ordinary path construction or backend
  operations.
- Fully implementing overlay whiteouts or copy-up behavior in v1.
- Guaranteeing host symlink creation/read-link support where Kyo does not expose
  the required public APIs.

## Public Capabilities

The API has two algebras.

```scala
trait ReadonlyVfs:
  def exists(path: VPath): Boolean < Sync
  def isDirectory(path: VPath): Boolean < Sync
  def isRegularFile(path: VPath): Boolean < Sync
  def isSymbolicLink(path: VPath): Boolean < Sync

  def realPath(path: VPath): VPath < (Sync & Abort[VfsError])
  def read(path: VPath): String < (Sync & Abort[VfsError])
  def read(path: VPath, charset: Charset): String < (Sync & Abort[VfsError])
  def readBytes(path: VPath): Span[Byte] < (Sync & Abort[VfsError])
  def readLines(path: VPath): Chunk[String] < (Sync & Abort[VfsError])
  def readLines(path: VPath, charset: Charset): Chunk[String] < (Sync & Abort[VfsError])
  def stat(path: VPath): VfsStat < (Sync & Abort[VfsError])
  def list(path: VPath): Chunk[VPath] < (Sync & Abort[VfsError])
  def list(path: VPath, glob: String): Chunk[VPath] < (Sync & Abort[VfsError])
  def walk(
    path: VPath,
    maxDepth: Int = Int.MaxValue,
    followLinks: Boolean = false
  ): Stream[VPath, Sync & Scope & Abort[VfsError]]
  def readSymlink(path: VPath): VPath < (Sync & Abort[VfsError])

  def readStream(path: VPath): Stream[String, Sync & Scope & Abort[VfsError]]
  def readStream(
    path: VPath,
    charset: Charset
  ): Stream[String, Sync & Scope & Abort[VfsError]]
  def readBytesStream(path: VPath): Stream[Chunk[Byte], Sync & Scope & Abort[VfsError]]
  def readLinesStream(path: VPath): Stream[String, Sync & Scope & Abort[VfsError]]
  def readLinesStream(
    path: VPath,
    charset: Charset
  ): Stream[String, Sync & Scope & Abort[VfsError]]

trait Vfs extends ReadonlyVfs:
  def asReadonly: ReadonlyVfs

  def write(
    path: VPath,
    value: String,
    createFolders: Boolean = true
  ): Unit < (Sync & Abort[VfsError])
  def write(
    path: VPath,
    value: String,
    charset: Charset,
    createFolders: Boolean = true
  ): Unit < (Sync & Abort[VfsError])
  def writeBytes(
    path: VPath,
    value: Span[Byte],
    createFolders: Boolean = true
  ): Unit < (Sync & Abort[VfsError])
  def writeLines(
    path: VPath,
    value: Chunk[String],
    createFolders: Boolean = true
  ): Unit < (Sync & Abort[VfsError])
  def append(
    path: VPath,
    value: String,
    createFolders: Boolean = true
  ): Unit < (Sync & Abort[VfsError])
  def append(
    path: VPath,
    value: String,
    charset: Charset,
    createFolders: Boolean = true
  ): Unit < (Sync & Abort[VfsError])
  def appendBytes(
    path: VPath,
    value: Span[Byte],
    createFolders: Boolean = true
  ): Unit < (Sync & Abort[VfsError])
  def appendLines(
    path: VPath,
    value: Chunk[String],
    createFolders: Boolean = true
  ): Unit < (Sync & Abort[VfsError])
  def truncate(path: VPath, size: VfsSize): Unit < (Sync & Abort[VfsError])
  def setLastModified(
    path: VPath,
    timestamp: VfsTimestamp
  ): Unit < (Sync & Abort[VfsError])

  def mkDir(path: VPath): Unit < (Sync & Abort[VfsError])
  def mkFile(path: VPath): Unit < (Sync & Abort[VfsError])
  def move(
    from: VPath,
    to: VPath,
    replaceExisting: Boolean = false
  ): Unit < (Sync & Abort[VfsError])
  def copy(
    from: VPath,
    to: VPath,
    replaceExisting: Boolean = false
  ): Unit < (Sync & Abort[VfsError])
  def remove(path: VPath): Boolean < (Sync & Abort[VfsError])
  def removeExisting(path: VPath): Unit < (Sync & Abort[VfsError])
  def removeAll(path: VPath): Unit < (Sync & Abort[VfsError])
  def createSymlink(path: VPath, target: VPath): Unit < (Sync & Abort[VfsError])

  def writeStream(
    path: VPath,
    append: Boolean = false,
    createFolders: Boolean = true
  ): Vfs.WriteHandle < (Sync & Scope & Abort[VfsError])
```

`ReadonlyVfs` is the advertised capability for read-only APIs:

```scala
def loadConfig(path: VPath): Config < (Sync & Env[ReadonlyVfs] & Abort[VfsError])
```

`Vfs` is the advertised capability for mutating APIs:

```scala
def saveConfig(path: VPath, config: Config): Unit < (Sync & Env[Vfs] & Abort[VfsError])
```

A writable filesystem can be explicitly viewed as read-only:

```scala
for
  vfs <- Vfs.inMemory.init
  config <- ReadonlyVfs.run(vfs.asReadonly) {
    loadConfig(VPath / "config.json")
  }
yield config
```

`Env[Vfs]` does not automatically satisfy `Env[ReadonlyVfs]`. Callers must
intentionally provide the read-only view.

## Companion API

Companion helpers mirror common Kyo service patterns.

```scala
object ReadonlyVfs:
  def get: ReadonlyVfs < Env[ReadonlyVfs]
  def run[A, S](vfs: ReadonlyVfs)(value: A < (Env[ReadonlyVfs] & S)): A < S
  def from(vfs: Vfs): ReadonlyVfs

  object host:
    def init(root: kyo.Path): ReadonlyVfs < Sync

  object mounted:
    def init(mounts: ReadonlyMount*): ReadonlyVfs < (Sync & Abort[VfsError])

object Vfs:
  def get: Vfs < Env[Vfs]
  def run[A, S](vfs: Vfs)(value: A < (Env[Vfs] & S)): A < S

  object inMemory:
    def init: Vfs < Sync

  object host:
    def init(root: kyo.Path): Vfs < Sync

  object mounted:
    def init(mounts: Mount*): Vfs < (Sync & Abort[VfsError])

  trait WriteHandle:
    def writeBytes(chunk: Chunk[Byte]): Unit < (Sync & Abort[VfsError])
    def writeString(value: String): Unit < (Sync & Abort[VfsError])
    def writeString(
      value: String,
      charset: Charset
    ): Unit < (Sync & Abort[VfsError])
```

Overlay factories are intentionally omitted from the v1 companion API. Their
reserved shape is documented later so the v1 abstractions leave room for them.

## Path Model

`VPath` is a virtual, Unix-style, case-sensitive path type.

Rules:

- `/a/b` and `a/b` are valid virtual paths.
- `/Readme.md` and `/README.md` are distinct.
- `.` is normalized away.
- `..` is resolved syntactically but cannot escape the virtual root.
- `~` is a literal segment unless parsing with an explicit `VPathContext`.
- `VPath.root` represents absolute `/`.
- `VPath.cwd` represents the relative current-directory marker `.`; it is not
  tied to process state or a backend.
- Host-specific concepts like drive letters stay behind `HostVfs`.

Construction:

```scala
VPath / "etc" / "app.conf"
VPath("var", "data", "file.txt")
VPath.root
VPath.cwd / "src" / "main.scala"
```

Parsing:

```scala
final case class VPathContext(
  home: Maybe[VPath] = Absent,
  cwd: VPath = VPath.root
)

VPath.parse("src/main.scala")
VPath.parse("~/config", VPathContext(home = Maybe(VPath / "home" / "damian")))
```

`VPath.parse(input)` does no home expansion and preserves whether the input is
absolute or relative. `VPath.parse(input, context)` expands a leading `~` only
if `context.home` is present and resolves relative inputs against `context.cwd`,
so it returns an absolute path; otherwise, a leading `~` fails with
`VfsError.NoHomeDirectory`.

Resolution:

```scala
path.resolveAgainst(base)
```

Rules:

- absolute `path` values resolve to themselves
- relative `path` values resolve under `base`
- `base` must be absolute
- `..` is normalized during resolution but cannot escape root

Useful operations:

```scala
path.parts
path.name
path.parent
path.isAbsolute
path.relativeTo(prefix)
path.resolve(target)
path.resolveAgainst(base)
```

For symlinks, targets are stored as `VPath`. Relative symlink targets resolve
relative to the symlink parent. Absolute targets resolve from the VFS root.
Resolution detects loops and fails with `VfsError.SymlinkLoop`.

## Metadata

Stats use small domain types instead of raw `Long` values.

```scala
opaque type VfsSize = Long
object VfsSize:
  def bytes(value: Long): VfsSize
  def zero: VfsSize

  extension (self: VfsSize)
    def toBytes: Long
    def render: String

  given Render[VfsSize]

opaque type VfsTimestamp = Long
object VfsTimestamp:
  def epochMillis(value: Long): VfsTimestamp
  def now: VfsTimestamp < Sync

  extension (self: VfsTimestamp)
    def toEpochMillis: Long
    def render: String

  given Render[VfsTimestamp]

enum VfsEntryType:
  case File, Directory, Symlink

final case class VfsStat(
  entryType: VfsEntryType,
  size: VfsSize,
  lastModified: VfsTimestamp
)
```

`VfsSize.render` should produce compact human-readable units such as `512 B`,
`2 KB`, and `1.5 MB`.

`VfsTimestamp.render` should initially use deterministic ISO-8601 UTC, for
example `2026-06-16T09:30:00Z`.

Directory and symlink sizes are `VfsSize.zero` in v1.

## Errors

All public operations use `Abort[VfsError]`.

```scala
sealed abstract class VfsError(message: String, cause: String | Throwable = "")(using Frame)
    extends Exception(message, cause match
        case throwable: Throwable => throwable
        case _                    => null
    )

object VfsError:
  final case class NotFound(path: VPath)(using Frame)
      extends VfsError(s"Path not found: $path")
  final case class AlreadyExists(path: VPath)(using Frame)
      extends VfsError(s"Path already exists: $path")
  final case class NotDirectory(path: VPath)(using Frame)
      extends VfsError(s"Expected a directory: $path")
  final case class IsDirectory(path: VPath)(using Frame)
      extends VfsError(s"Expected a file but found a directory: $path")
  final case class DirectoryNotEmpty(path: VPath)(using Frame)
      extends VfsError(s"Directory is not empty: $path")
  final case class AccessDenied(path: VPath)(using Frame)
      extends VfsError(s"Access denied: $path")
  final case class Unsupported(path: VPath, operation: String)(using Frame)
      extends VfsError(s"Unsupported VFS operation '$operation' for $path")
  final case class SymlinkLoop(path: VPath)(using Frame)
      extends VfsError(s"Symlink loop detected at $path")
  final case class InvalidPath(input: String, reason: String)(using Frame)
      extends VfsError(s"Invalid virtual path '$input': $reason")
  final case class NoHomeDirectory(input: String)(using Frame)
      extends VfsError(s"Cannot expand '$input' without a VPathContext home")
  final case class BackendFailure(
    path: VPath,
    operation: String,
    cause: Throwable
  )(using Frame) extends VfsError(s"Backend failed during '$operation' for $path", cause)
```

The host backend translates Kyo `FileException` values into `VfsError` where
possible:

- `FileNotFoundException` -> `VfsError.NotFound`
- `FileAccessDeniedException` -> `VfsError.AccessDenied`
- `FileIsADirectoryException` -> `VfsError.IsDirectory`
- `FileNotADirectoryException` -> `VfsError.NotDirectory`
- `FileAlreadyExistsException` -> `VfsError.AlreadyExists`
- unknown file exceptions -> `VfsError.BackendFailure`

The VFS API does not split read/write/filesystem errors into separate public
traits in v1. The read-only/writable algebra split carries the main capability
boundary, and VFS backends have cross-cutting failure modes.

## Backend Semantics

### InMemoryVfs

- Writable, case-sensitive, thread/fiber safe.
- Backed by an internal tree guarded by Kyo concurrency primitives, likely
  `Meter` or atomic state.
- Supports files, directories, and symlinks.
- Supports all v1 operations, including line reads/writes, read streams, write
  streams, walk, stat, move, copy, and remove.
- Uses `\n` for `writeLines` and `appendLines`.
- Uses `VfsTimestamp.now` for mutation timestamps.
- `asReadonly` exposes a read-only view over the same underlying instance.

### HostVfs

- Writable and confined to a configured `kyo.Path` root.
- Virtual `/a/b` maps to `<root>/a/b`.
- `..` cannot escape the virtual root.
- Uses Kyo `Path` operations for host I/O.
- Supports reads, writes, lines, listing, walking, stat, move, copy, and remove
  where Kyo `Path` exposes matching operations.
- Symlink creation/read-link can return `VfsError.Unsupported` in v1 if Kyo does
  not expose enough public API.
- Existing host symlinks must not allow escaping the configured root. Any
  resolved path outside root fails with `VfsError.AccessDenied`.

### MountedVfs

`MountedVfs` is a writable router implemented in v1.

```scala
final case class Mount(
  at: VPath,
  vfs: Vfs,
  root: VPath = VPath.root
)
```

Routing uses longest virtual prefix match:

```text
/app/config.json
Mount(/app -> mem:/)
=> mem:/config.json
```

Rules:

- Duplicate mount points are rejected at init.
- Relative mount points are rejected.
- If no mount matches, fail with `VfsError.NotFound`.
- Operations cannot cross backend boundaries unless the operation is explicitly
  router-aware.
- In v1, `move` and `copy` across different mounts return
  `VfsError.Unsupported`; same-mount operations delegate.
- `list` synthesizes mount directories. With `/app` and `/data` mounts, listing
  `/` returns `/app` and `/data` even if no root mount exists.

### ReadonlyMountedVfs

```scala
final case class ReadonlyMount(
  at: VPath,
  vfs: ReadonlyVfs,
  root: VPath = VPath.root
)
```

`ReadonlyMountedVfs` uses the same routing semantics as `MountedVfs`, but
exposes only the read-only algebra.

### OverlayVfs

Overlay support is designed but deferred. These factories are the reserved
future shape, not part of v1:

```scala
Vfs.overlay.init(upper: Vfs, lower: ReadonlyVfs, rest: ReadonlyVfs*)
ReadonlyVfs.overlay.init(layers: ReadonlyVfs*)
```

Expected semantics:

- reads, stat, and list search upper to lower
- writes go to the writable upper layer
- deletion of lower entries requires whiteouts
- copy-up policy is explicit, not implicit

Overlay should get a separate focused design before implementation because
remove and list behavior can surprise users without clear whiteout semantics.

## Path-First Ergonomics

`VPath` extension methods delegate to the appropriate service from `Env`.

Read-only methods require `Env[ReadonlyVfs]`:

```scala
(VPath / "config.json").read
(VPath / "config.json").readLines
(VPath / "config.json").stat
```

Writable methods require `Env[Vfs]`:

```scala
(VPath / "config.json").write("{}")
(VPath / "logs" / "app.log").append("started\n")
(VPath / "cache").removeAll
```

This keeps both styles available:

```scala
vfs.read(VPath / "notes.txt")

Vfs.run(vfs) {
  (VPath / "notes.txt").write("hello")
}
```

A program that has only `Env[Vfs]` can still read through the explicit service
value because `Vfs` extends `ReadonlyVfs`. Path-first read methods intentionally
require `Env[ReadonlyVfs]`; callers that want path-first reads inside a writable
scope should explicitly provide `vfs.asReadonly` as well.

## Testing Strategy

Tests should cover:

- `VPath` construction, normalization, parsing, `~` context expansion, and
  escape rejection.
- `VfsSize` and `VfsTimestamp` rendering.
- Common backend behavior shared by all writable `Vfs` implementations.
- Common backend behavior shared by all `ReadonlyVfs` implementations.
- In-memory behavior, including symlink resolution and loop detection.
- In-memory thread/fiber safety under concurrent mutation.
- Host confinement, including syntactic `..` escapes and host symlink escape
  protection.
- Mounted routing, longest-prefix match, synthetic mount directories, and
  cross-mount unsupported move/copy.
- Read-only signatures through small compile-time usage examples where
  practical, plus runtime coverage through read-only routers and views.

At minimum, changes should be verified with compile and tests on the JVM
platform for the touched module:

```sh
./mill kymora.vfs.jvm.compile
./mill kymora.vfs.jvm.test
```

## Open Implementation Notes

- Verify whether Kyo exposes enough public API for efficient host streaming
  writes. If not, host `writeStream` can use a buffered fallback or return
  `VfsError.Unsupported` in v1.
- `MountedVfs.init` and `ReadonlyVfs.mounted.init` reject duplicate or relative
  mount points by failing with `VfsError.InvalidPath`.
