package io.eleven19.kymora.vfs.internal

import java.nio.file.Files as JFiles
import java.nio.file.Path as JPath

import kyo.*

private[vfs] object HostPlatform:

    def createSymlink(path: Path, target: Path)(using Frame): Unit < Sync =
        Sync.defer {
            JFiles.createSymbolicLink(JPath.of(path.toString), JPath.of(target.toString))
            ()
        }
end HostPlatform
