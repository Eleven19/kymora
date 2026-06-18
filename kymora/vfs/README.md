# kymora-vfs

`kymora-vfs` provides virtual filesystem access as first-class Kyo effects.
Programs depend on `ReadonlyVfs` when they only inspect files and `Vfs` when
they mutate files. Callers provide a concrete backend at the edge of the
program.

## Overview

- `VPath` is the platform-neutral path type. It always uses `/`, normalizes `.`
  and `..`, and can represent absolute or relative virtual paths.
- `ReadonlyVfs` is the read capability. It supports existence checks, metadata,
  text/byte reads, directory listings, walking, streams, and symlink reads.
- `Vfs` is the write capability. It extends read access with writes, appends,
  `mkDir`, recursive `mkDirs`, scoped temp directories, copy, move, remove,
  symlink creation, truncation, and timestamp updates.
- `VfsError` is the recoverable error model used with `Abort[VfsError]`.
- Backends include in-memory, host-rooted, and mounted filesystems. Mounted VFS
  routes absolute mount points to other backends.

## Basic Usage

```scala
import io.eleven19.kymora.vfs.*
import kyo.*

val notes =
  for
    _    <- (VPath.root / "notes" / "today.txt").write("ship docs")
    text <- (VPath.root / "notes" / "today.txt").read
  yield text

val result =
  for
    backend <- Vfs.inMemory.init
    text    <- Vfs.run(backend)(notes)
  yield text
```

`Vfs.run` handles both `Vfs` and `ReadonlyVfs`, so mixed read/write programs only
need one handler.

## Directories And Temp Dirs

Use `mkDir` when creating one known directory whose parent already exists, and
`mkDirs` when creating an output layout with missing parents:

```scala
val program =
  for
    _ <- (VPath.root / "build").mkDir
    _ <- (VPath.root / "build" / "classes" / "main").mkDirs
    _ <- (VPath.root / "build" / "classes" / "main" / "App.class").write("compiled")
  yield ()
```

Use `Vfs.tempDir` for scoped scratch space inside the active backend. The
directory is removed when the surrounding `Scope` exits:

```scala
val scratch =
  Scope.run {
    for
      dir  <- Vfs.tempDir(prefix = "compile-")
      file  = dir / "analysis.txt"
      _    <- file.write("temporary")
      text <- file.read
    yield text
  }
```

Use `path.tempDir` to place scratch space under a specific parent:

```scala
val underWork =
  Scope.run {
    for
      _   <- (VPath.root / "work").mkDirs
      dir <- (VPath.root / "work").tempDir(prefix = "scratch-")
      _   <- (dir / "out.txt").write("ok")
    yield dir
  }
```

For real host-backed temporary work areas, create a scoped host temp VFS:

```scala
val result =
  Scope.run {
    for
      temp <- Vfs.host.tempDir(prefix = "kymora-build-")
      file  = temp.root / "report.txt"
      _    <- temp.vfs.write(file, "host-backed scratch")
      text <- temp.vfs.read(file)
    yield text
  }
```

## Read-Only Programs

Use `ReadonlyVfs` when a function should not require write authority:

```scala
import io.eleven19.kymora.vfs.*
import kyo.*

def loadConfig(path: VPath)(using Frame): String < (ReadonlyVfs & Sync & Abort[VfsError]) =
  path.read

val program =
  for
    backend <- Vfs.inMemory.init
    _       <- backend.write(VPath.root / "config.json", """{"mode":"dev"}""")
    text    <- ReadonlyVfs.run(backend.asReadonly)(loadConfig(VPath.root / "config.json"))
  yield text
```

## Backends

```scala
// Ephemeral in-memory filesystem.
val memory: Vfs.Backend < Sync =
  Vfs.inMemory.init

// Host filesystem confined beneath a root directory.
val host: Vfs.Backend < Sync =
  Vfs.host.init(Path("/tmp/kymora"))

// Mount multiple backends into one virtual filesystem.
val mounted: Vfs.Backend < (Sync & Abort[VfsError]) =
  Vfs.mounted.init(
    Mount(VPath.root / "work", workBackend),
    Mount(VPath.root / "cache", cacheBackend),
  )
```

Host backends expose the configured host root as virtual `/` and reject paths
that escape that root. Mounted backends preserve mount-space paths in errors and
metadata so callers do not need to know which backend served an operation.

## Error Handling

VFS operations fail through `Abort[VfsError]` rather than throwing ordinary
filesystem exceptions:

```scala
val attempt =
  for
    backend <- Vfs.inMemory.init
    result  <- Abort.run[VfsError] {
                 Vfs.run(backend)((VPath.root / "missing.txt").read)
               }
  yield result
```

Pattern match on `VfsError.NotFound`, `AccessDenied`, `InvalidPath`, and the
other variants when a caller can recover from filesystem failures.
