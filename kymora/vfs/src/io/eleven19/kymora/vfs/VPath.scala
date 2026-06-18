package io.eleven19.kymora.vfs

import kyo.*

/** A normalized virtual path used by [[Vfs]] and [[ReadonlyVfs]] implementations.
  *
  * `VPath` is independent from the host operating system. It always uses `/` as its separator, tracks whether a path is
  * absolute, and normalizes empty segments, `.`, and `..` during construction.
  *
  * {{{
  * val source = VPath.root / "src" / "Main.scala"
  * val cache  = VPath("target") / "cache"
  *
  * source.show // "/src/Main.scala"
  * cache.show  // "target/cache"
  * }}}
  */
opaque type VPath = VPath.Data

object VPath:

    /** Public representation backing the opaque [[VPath]] type. */
    final case class Data(isAbsolute: Boolean, parts: Chunk[String]):

        /** Renders the path in virtual path syntax. */
        def show: String =
            val body = parts.mkString("/")
            if isAbsolute then s"/$body"
            else if body.isEmpty then "."
            else body
    end Data

    given CanEqual[VPath, VPath] = CanEqual.derived

    /** The absolute virtual filesystem root, rendered as `/`. */
    val root: VPath = Data(isAbsolute = true, Chunk.empty)

    /** The relative current-directory path, rendered as `.`. */
    val cwd: VPath = Data(isAbsolute = false, Chunk.empty)

    /** A path segment accepted by [[apply]] and `/` path construction. */
    type Part = String | VPath

    /** Builds a relative path from string segments or existing paths. */
    def apply(parts: Part*): VPath =
        normalize(isAbsolute = false, flatten(parts))

    /** Parses virtual path syntax without resolving `~` or relative paths. */
    def parse(input: String)(using Frame): VPath < Abort[VfsError] =
        Abort.get(Result.succeed(parseRaw(input)))

    /** Parses virtual path syntax using a context for `~` and relative paths.
      *
      * A leading `~` expands to `context.home`. Relative paths are resolved against `context.cwd`, which must be
      * absolute.
      */
    def parse(input: String, context: VPathContext)(using Frame): VPath < Abort[VfsError] =
        if input == "~" || input.startsWith("~/") then
            context.home match
                case Absent =>
                    Abort.fail(VfsError.NoHomeDirectory(input))
                case Present(home) =>
                    val suffix = input.stripPrefix("~").stripPrefix("/")
                    val target = if suffix.isEmpty then home else home / suffix
                    target.resolveAgainst(context.cwd)
        else parseRaw(input).resolveAgainst(context.cwd)

    /** Builds an absolute path by appending `part` to [[root]]. */
    infix def /(part: Part)(using Frame): VPath =
        root / part

    private def parseRaw(input: String): VPath =
        normalize(isAbsolute = input.startsWith("/"), split(input))

    private def split(input: String): Chunk[String] =
        Chunk.from(input.split("/", -1).toSeq)

    private def flatten(parts: Seq[Part]): Chunk[String] =
        Chunk.from(parts.flatMap {
            case value: String => value.split("/", -1).toSeq
            case value: VPath  => value.parts
        })

    private def normalize(isAbsolute: Boolean, raw: Chunk[String]): VPath =
        val normalized = raw.foldLeft(Vector.empty[String]) { (acc, part) =>
            part match
                case "" | "." => acc
                case ".." =>
                    if acc.nonEmpty && acc.last != ".." then acc.dropRight(1)
                    else if isAbsolute then acc
                    else acc :+ part
                case other => acc :+ other
        }
        Data(isAbsolute, Chunk.from(normalized))

    extension (self: VPath)
        /** Normalized path segments without separators. */
        def parts: Chunk[String] = self.parts

        /** Renders the path in virtual path syntax. */
        def show: String = self.show

        /** Whether this path starts at the virtual filesystem root. */
        def isAbsolute: Boolean = self.isAbsolute

        /** The last segment of the path, when present. */
        def name: Maybe[String] = self.parts.lastMaybe

        /** The parent path, or `Absent` for root and current directory. */
        def parent: Maybe[VPath] =
            if self.parts.isEmpty then Absent
            else Present(Data(self.isAbsolute, self.parts.init))

        /** Appends a segment or path and normalizes the result. */
        infix def /(part: Part)(using Frame): VPath =
            normalize(self.isAbsolute, self.parts ++ flatten(Seq(part)))

        /** Resolves this path against an absolute base path.
          *
          * Absolute paths are returned unchanged. Relative paths are appended to `base`; a relative `base` fails with
          * [[VfsError.InvalidPath]].
          */
        def resolveAgainst(base: VPath)(using Frame): VPath < Abort[VfsError] =
            if self.isAbsolute then self
            else if !base.isAbsolute then Abort.fail(VfsError.InvalidPath(base.show, "base must be absolute"))
            else Abort.get(Result.succeed(normalize(isAbsolute = true, base.parts ++ self.parts)))

        /** Computes this path relative to `prefix`.
          *
          * Fails when this path is not under `prefix` or when one path is absolute and the other is relative.
          */
        def relativeTo(prefix: VPath)(using Frame): VPath < Abort[VfsError] =
            if self.isAbsolute != prefix.isAbsolute || self.parts.take(prefix.parts.size) != prefix.parts then
                Abort.fail(VfsError.InvalidPath(self.show, s"not under ${prefix.show}"))
            else Abort.get(Result.succeed(Data(isAbsolute = false, self.parts.drop(prefix.parts.size))))

        /** Resolves `target` from this path, preserving absolute targets. */
        def resolve(target: VPath)(using Frame): VPath =
            if target.isAbsolute then target
            else normalize(self.isAbsolute, self.parts ++ target.parts)
end VPath

/** Resolution context used when parsing user-facing path strings.
  *
  * `home` controls `~` expansion. `cwd` is the absolute directory used for relative paths.
  */
final case class VPathContext(
    home: Maybe[VPath] = Absent,
    cwd: VPath = VPath.root
)
