package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.VPath
import kyo.*

/** A Mill-style `PathRef` analogue: a virtual filesystem path bundled with the
  * fingerprint of its contents (or a "quick" hash). Wire-encoded as a single
  * colon-separated string: `vref:<tag>:<fp>:<path>`.
  *
  * Tags:
  *   - `v0c` — content-hash (default)
  *   - `v0q` — quick-hash (size+mtime); cheaper but weaker
  *
  * The path component preserves embedded colons; parsing splits the wire form
  * into at most 4 segments so the path keeps any inner `:`.
  */
final case class VfsPathRef(path: VPath, fingerprint: Fingerprint, quick: Boolean = false)
  derives CanEqual

object VfsPathRef:
  private val Prefix = "vref"

  def render(ref: VfsPathRef): String =
    val tag = if ref.quick then "v0q" else "v0c"
    s"$Prefix:$tag:${ref.fingerprint.value}:${ref.path.show}"

  def parse(s: String): Result[ParseError, VfsPathRef] =
    // Wire form: vref:<tag>:<algo>:<hex>:<path>
    // Fingerprint is always `algo:hex` (one colon), so we split into at most 5
    // segments and the path component keeps any embedded colons.
    s.split(":", 5) match
      case Array(p, tag, algo, hex, path) if p == Prefix =>
        val fp = Fingerprint.unsafe(s"$algo:$hex")
        tag match
          case "v0c" => Result.succeed(VfsPathRef(VPath(path), fp, quick = false))
          case "v0q" => Result.succeed(VfsPathRef(VPath(path), fp, quick = true))
          case _     => Result.fail(ParseError.UnknownVfsPathRefTag(tag))
      case _ => Result.fail(ParseError.MalformedVfsPathRef(s))

  given schema: Schema[VfsPathRef] =
    Schema.init[VfsPathRef](
      writeFn = (v, w) => w.string(render(v)),
      readFn = r =>
        val s = r.string()
        parse(s) match
          case Result.Success(v) => v
          case _                 =>
            throw new IllegalArgumentException(s"Malformed VfsPathRef: $s")
    )

  /** Override the default Hashable so dependents' valueHash equals the embedded
    * fingerprint directly — enabling the early-cutoff property from spec §3.5.
    */
  given Hashable[VfsPathRef] = (r: VfsPathRef) => r.fingerprint
end VfsPathRef
