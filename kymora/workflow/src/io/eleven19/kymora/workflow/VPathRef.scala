package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.ReadonlyVfs
import io.eleven19.kymora.vfs.VPath
import io.eleven19.kymora.vfs.VfsError
import kyo.*

/** A Mill-style `PathRef` analogue: a virtual filesystem path bundled with the fingerprint of its contents (or a
  * "quick" hash). Wire-encoded as a single colon-separated string: `vref:<tag>:<fp>:<path>`.
  *
  * Tags:
  *   - `v0c` — content-hash (default)
  *   - `v0q` — quick-hash (size+mtime); cheaper but weaker
  *
  * The path component preserves embedded colons; parsing splits the wire form into at most 4 segments so the path keeps
  * any inner `:`.
  */
final case class VPathRef(path: VPath, fingerprint: Fingerprint, quick: Boolean = false) derives CanEqual

object VPathRef:
    private val Prefix = "vref"

    /** Builds a content-hashed `VPathRef` by reading the file at `path` and fingerprinting its bytes via Blake3.
      *
      * The resulting reference is `quick = false`. See spec §3.5: equal contents across different paths produce equal
      * fingerprints, enabling the engine's early-cutoff property for downstream tasks.
      */
    def of(path: VPath, vfs: ReadonlyVfs.Backend)(using Frame): VPathRef < (Sync & Abort[VfsError]) =
        for span <- vfs.readBytes(path)
        yield VPathRef(path, Fingerprint.ofBytes(Chunk.from(span.toArray)), quick = false)

    /** Builds a quick-hashed `VPathRef` from `size|mtime` rather than the full file bytes. Cheaper than [[of]] but only
      * detects size/mtime changes — see spec §3.5 and §4.5.
      */
    def quick(path: VPath, vfs: ReadonlyVfs.Backend)(using Frame): VPathRef < (Sync & Abort[VfsError]) =
        for stat <- vfs.stat(path)
        yield
            val token      = s"${stat.size.toBytes}|${stat.lastModified.toEpochMillis}"
            val tokenBytes = Chunk.from(token.getBytes("UTF-8"))
            VPathRef(path, Fingerprint.ofBytes(tokenBytes), quick = true)

    def render(ref: VPathRef): String =
        val tag = if ref.quick then "v0q" else "v0c"
        s"$Prefix:$tag:${ref.fingerprint.value}:${ref.path.show}"

    def parse(s: String): Result[ParseError, VPathRef] =
        // Wire form: vref:<tag>:<algo>:<hex>:<path>
        // Fingerprint is always `algo:hex` (one colon), so we split into at most 5
        // segments and the path component keeps any embedded colons.
        s.split(":", 5) match
            case Array(p, tag, algo, hex, path) if p == Prefix =>
                val fp = Fingerprint.unsafe(s"$algo:$hex")
                tag match
                    case "v0c" => Result.succeed(VPathRef(VPath(path), fp, quick = false))
                    case "v0q" => Result.succeed(VPathRef(VPath(path), fp, quick = true))
                    case _     => Result.fail(ParseError.UnknownVPathRefTag(tag))
            case _ => Result.fail(ParseError.MalformedVPathRef(s))

    given schema: Schema[VPathRef] =
        Schema.init[VPathRef](
            writeFn = (v, w) => w.string(render(v)),
            readFn = r =>
                val s = r.string()
                parse(s) match
                    case Result.Success(v) => v
                    // kyo-schema's `readFn: Reader => A` is total — there is no
                    // failure channel to thread a Result through. The structured
                    // ParseError variant doubles as a RuntimeException so callers
                    // can `catch ParseError =>` or pattern-match on the cause.
                    case Result.Failure(err: ParseError) => throw err
                    case _                               => throw ParseError.MalformedVPathRef(s)
        )

    /** Override the default Hashable so dependents' valueHash equals the embedded fingerprint directly — enabling the
      * early-cutoff property from spec §3.5.
      */
    given Hashable[VPathRef] = (r: VPathRef) => r.fingerprint

    /** Order-sensitive aggregate fingerprint over a chunk of refs.
      *
      * Used by the engine's `Task.Sources` valueHash and by the matching `Hashable[Chunk[VPathRef]]` so a parameterized
      * `Task.cached[A, Chunk[VPathRef]]` matches the engine-side dep fingerprint exactly. Reordering or duplicating
      * refs changes the aggregate.
      */
    def aggregateFingerprint(refs: Chunk[VPathRef]): Fingerprint =
        if refs.isEmpty then Fingerprint.ofBytes(Chunk.from("sources:empty".getBytes))
        else Fingerprint.ofBytes(Chunk.from(refs.map(_.fingerprint.value).mkString("|").getBytes))

    given Hashable[Chunk[VPathRef]] = (refs: Chunk[VPathRef]) => aggregateFingerprint(refs)
end VPathRef
