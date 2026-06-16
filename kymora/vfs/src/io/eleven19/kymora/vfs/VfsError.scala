package io.eleven19.kymora.vfs

import kyo.*

/** Typed failure model for virtual filesystem operations.
  *
  * Implementations should translate backend-specific failures into these errors so callers can recover with ordinary
  * Kyo `Abort[VfsError]` handling.
  */
sealed abstract class VfsError(message: String, cause: Throwable = null)(using Frame) extends Exception(message, cause)

object VfsError:
    /** The requested path does not exist. */
    final case class NotFound(path: VPath)(using Frame) extends VfsError(s"Path not found: ${path.show}")

    /** The operation cannot continue because the target already exists. */
    final case class AlreadyExists(path: VPath)(using Frame) extends VfsError(s"Path already exists: ${path.show}")

    /** A directory was required, but the path refers to a different entry kind. */
    final case class NotDirectory(path: VPath)(using Frame) extends VfsError(s"Expected a directory: ${path.show}")

    /** A regular file was required, but the path refers to a directory. */
    final case class IsDirectory(path: VPath)(using Frame)
        extends VfsError(s"Expected a file but found a directory: ${path.show}")

    /** A directory removal failed because it still contains entries. */
    final case class DirectoryNotEmpty(path: VPath)(using Frame)
        extends VfsError(s"Directory is not empty: ${path.show}")

    /** The backend denied access to the path or escaped a configured boundary. */
    final case class AccessDenied(path: VPath)(using Frame) extends VfsError(s"Access denied: ${path.show}")

    /** The selected backend does not support this operation for the path. */
    final case class Unsupported(path: VPath, operation: String)(using Frame)
        extends VfsError(s"Unsupported VFS operation '$operation' for ${path.show}")

    /** Symlink resolution detected a cycle. */
    final case class SymlinkLoop(path: VPath)(using Frame) extends VfsError(s"Symlink loop detected at ${path.show}")

    /** A path string or path relationship is invalid for the requested operation. */
    final case class InvalidPath(input: String, reason: String)(using Frame)
        extends VfsError(s"Invalid virtual path '$input': $reason")

    /** A path containing `~` was parsed without a configured home directory. */
    final case class NoHomeDirectory(input: String)(using Frame)
        extends VfsError(s"Cannot expand '$input' without a VPathContext home")

    /** A backend raised an unexpected exception while performing an operation. */
    final case class BackendFailure(path: VPath, operation: String, cause: Throwable)(using Frame)
        extends VfsError(s"Backend failed during '$operation' for ${path.show}", cause)

    given Render[VfsError] = Render.from(_.getMessage)
end VfsError
