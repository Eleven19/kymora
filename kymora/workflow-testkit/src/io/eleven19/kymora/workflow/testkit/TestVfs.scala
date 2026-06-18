package io.eleven19.kymora.workflow.testkit

import io.eleven19.kymora.vfs.*
import kyo.*

/** Container returned by [[TestVfs.tempDir]]: a writable [[Vfs.Backend]] and the
  * absolute [[VPath]] that names its root inside that VFS. */
final case class TestVfsHandle(vfs: Vfs.Backend, root: VPath)

object TestVfs:

  /** A [[Scope]]-bound fresh working area for tests.
    *
    * The handle's [[Vfs.Backend]] is a host-backed VFS rooted at a freshly created OS
    * temp directory (via Kyo's cross-platform `Path.tempDir`). The temp
    * directory — and therefore everything written through the VFS — is
    * automatically removed when the surrounding [[Scope]] exits.
    *
    * The [[VPath]] returned as `root` is the virtual filesystem root of the
    * returned VFS, so callers can simply write to `root / "some" / "file"`.
    */
  def tempDir(using Frame): TestVfsHandle < (Sync & Scope & Abort[FileFsException]) =
    for
      hostRoot <- Path.tempDir("kymora-testkit-")
      vfs      <- Vfs.host.init(hostRoot)
    yield TestVfsHandle(vfs, VPath.root)

end TestVfs
