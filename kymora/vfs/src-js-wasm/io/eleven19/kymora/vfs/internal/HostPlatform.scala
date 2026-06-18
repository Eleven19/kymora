package io.eleven19.kymora.vfs.internal

import kyo.*
import scala.scalajs.js
import scala.scalajs.js.annotation.*

@js.native
@JSImport("node:fs", JSImport.Namespace)
private object NodeFs extends js.Object:
    def symlinkSync(target: String, path: String): Unit = js.native
end NodeFs

private[vfs] object HostPlatform:

    def createSymlink(path: Path, target: Path)(using Frame): Unit < Sync =
        Sync.defer(NodeFs.symlinkSync(target.toString, path.toString))
end HostPlatform
