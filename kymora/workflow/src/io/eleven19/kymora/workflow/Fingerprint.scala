package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.internal.Blake3
import kyo.*

/** Stable content fingerprint used for workflow cache decisions.
  *
  * Public fingerprints render as strings such as `blake3:<hex>`. The opaque type prevents accidentally mixing hashes
  * with arbitrary strings.
  */
opaque type Fingerprint = String

object Fingerprint:
    /** Wrap a pre-existing fingerprint string without recomputing the hash.
      *
      * Intended for decoders and tests where the engine has already produced the canonical `algo:hex` form. Does not
      * validate the algorithm prefix.
      */
    def unsafe(s: String): Fingerprint = s

    /** Canonical Blake3 fingerprint of `bytes`, formatted as `blake3:<hex>`. */
    def ofBytes(bytes: Chunk[Byte]): Fingerprint =
        s"blake3:${Blake3.hex(bytes)}"

    extension (fp: Fingerprint) def value: String = fp

    given schema: Schema[Fingerprint] =
        Schema.init[Fingerprint](
            writeFn = (v, w) => w.string(v),
            readFn = r => r.string()
        )

    given CanEqual[Fingerprint, Fingerprint] = CanEqual.derived
end Fingerprint
