package io.eleven19.kymora.workflow.internal

import kyo.*

/** Canonical wire format for hashing.
  *
  * Delegates to Protobuf — kyo-schema's Protobuf codec is field-order deterministic and byte-stable across runs, which
  * is what fingerprinting needs. We do NOT use this codec for stored blobs; the user-facing `Workflow.Runtime.codec`
  * controls that. See spec §3.2.
  */
private[workflow] given Canonical: Codec = Protobuf()
