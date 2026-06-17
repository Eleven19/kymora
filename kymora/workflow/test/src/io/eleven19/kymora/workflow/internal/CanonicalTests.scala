package io.eleven19.kymora.workflow.internal

import kyo.*
import kyo.test.*

final case class Sample(name: String, value: Int) derives Schema, CanEqual

class CanonicalTests extends Test[Any]:
  "Canonical encoding is deterministic across runs" in {
    val s = Sample("alice", 42)
    val a = Schema[Sample].encode(s)(using Canonical).toArray.toSeq
    val b = Schema[Sample].encode(s)(using Canonical).toArray.toSeq
    assert(a == b)
  }
  "Canonical encoding differs for differing inputs" in {
    val a = Schema[Sample].encode(Sample("alice", 1))(using Canonical).toArray.toSeq
    val b = Schema[Sample].encode(Sample("alice", 2))(using Canonical).toArray.toSeq
    assert(a != b)
  }
  "Canonical bytes are stable across two calls" in {
    val s = Sample("alice", 42)
    val a = Schema[Sample].encode(s)(using Canonical).toArray.toSeq
    val b = Schema[Sample].encode(s)(using Canonical).toArray.toSeq
    assert(a == b)
  }
end CanonicalTests
