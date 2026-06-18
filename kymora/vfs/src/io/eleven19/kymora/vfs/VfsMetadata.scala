package io.eleven19.kymora.vfs

import java.time.Instant

import kyo.*

/** A non-negative byte size reported by a virtual filesystem.
  *
  * The opaque representation keeps arithmetic and rendering decisions behind a small API while still allowing
  * implementations to store sizes efficiently.
  */
opaque type VfsSize = Long

object VfsSize:
    given CanEqual[VfsSize, VfsSize] = CanEqual.derived

    /** Builds a size from bytes, clamping negative values to zero. */
    def bytes(value: Long): VfsSize = value.max(0L)

    /** The zero-byte size. */
    val zero: VfsSize = 0L

    extension (self: VfsSize)
        /** Returns the raw byte count. */
        def toBytes: Long = self

        /** Renders the size using compact binary units such as `2 KB` or `1.5 MB`. */
        def render: String =
            if self < 1024L then s"$self B"
            else if self < 1024L * 1024L then format(self.toDouble / 1024d, "KB")
            else if self < 1024L * 1024L * 1024L then format(self.toDouble / (1024d * 1024d), "MB")
            else format(self.toDouble / (1024d * 1024d * 1024d), "GB")

    private def format(value: Double, unit: String): String =
        val rounded = BigDecimal(value).setScale(1, BigDecimal.RoundingMode.HALF_UP)
        if rounded.isWhole then s"${rounded.toLong} $unit" else s"$rounded $unit"

    given Render[VfsSize] = Render.from(_.render)
end VfsSize

/** A last-modified timestamp represented as milliseconds since the Unix epoch.
  *
  * VFS backends report timestamps through this opaque type so callers do not accidentally mix wall-clock milliseconds
  * with unrelated numeric values.
  */
opaque type VfsTimestamp = Long

object VfsTimestamp:
    given CanEqual[VfsTimestamp, VfsTimestamp] = CanEqual.derived

    /** Builds a timestamp from milliseconds since the Unix epoch. */
    def epochMillis(value: Long): VfsTimestamp = value

    /** Captures the current wall-clock time. */
    def now(using Frame): VfsTimestamp < Sync =
        Sync.defer(java.lang.System.currentTimeMillis())

    extension (self: VfsTimestamp)
        /** Returns milliseconds since the Unix epoch. */
        def toEpochMillis: Long = self

        /** Renders the timestamp as an ISO-8601 instant. */
        def render: String = Instant.ofEpochMilli(self).toString

    given Render[VfsTimestamp] = Render.from(_.render)
end VfsTimestamp

/** The kind of filesystem entry represented by [[VfsStat]]. */
enum VfsEntryType derives CanEqual:
    case File, Directory, Symlink

/** Metadata for a virtual filesystem entry.
  *
  * Returned by `stat` and path-first `path.stat`; use `entryType` to decide whether the path is a file, directory, or
  * symlink.
  */
final case class VfsStat(
    entryType: VfsEntryType,
    size: VfsSize,
    lastModified: VfsTimestamp
) derives CanEqual
