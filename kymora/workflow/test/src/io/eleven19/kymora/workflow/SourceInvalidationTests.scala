package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.VPath
import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

/** Closes #5 §3 — proves [[Task.Source]] now content-hashes via the
  * workflow runtime VFS rather than the prior path-string sentinel.
  *
  * End-to-end downstream-cache invalidation (mutate a file, assert a
  * dependent `Task.Cached` re-runs on the next `Workflow.run`) is gated
  * on cross-run value persistence (#5 §1) — the scheduler currently
  * treats any existing manifest as a HIT without comparing its stored
  * inputs hash against the recomputed one. Until §1 lands, these tests
  * assert the Source layer in isolation: the `VPathRef.fingerprint` it
  * produces.
  */
class SourceInvalidationTests extends Test[Any]:

  "Source.fingerprint changes when the file bytes change" in {
    val path = VPath("foo.txt")
    val src  = Task.source("src")(path)
    for
      driver <- WorkflowTestDriver.init
      _      <- driver.vfs.writeBytes(path, Span.from("v1".getBytes), createFolders = true)
      ref1   <- driver.run(src)
      _      <- driver.vfs.writeBytes(path, Span.from("a-different-payload".getBytes), createFolders = true)
      ref2   <- driver.run(src)
    yield
      assert(ref1.path == ref2.path)
      assert(ref1.fingerprint != ref2.fingerprint)
  }

  "Source.fingerprint stable when the file bytes are unchanged" in {
    val path = VPath("stable.txt")
    val src  = Task.source("src")(path)
    for
      driver <- WorkflowTestDriver.init
      _      <- driver.vfs.writeBytes(path, Span.from("same-bytes".getBytes), createFolders = true)
      ref1   <- driver.run(src)
      ref2   <- driver.run(src)
    yield assert(ref1.fingerprint == ref2.fingerprint)
  }

  "Equal bytes at different paths produce equal fingerprints (content addressed)" in {
    val pA = VPath("a.txt")
    val pB = VPath("b.txt")
    val srcA = Task.source("srcA")(pA)
    val srcB = Task.source("srcB")(pB)
    for
      driver <- WorkflowTestDriver.init
      _      <- driver.vfs.writeBytes(pA, Span.from("same".getBytes), createFolders = true)
      _      <- driver.vfs.writeBytes(pB, Span.from("same".getBytes), createFolders = true)
      refA   <- driver.run(srcA)
      refB   <- driver.run(srcB)
    yield
      assert(refA.path != refB.path)
      assert(refA.fingerprint == refB.fingerprint)
  }

  "sourceQuick produces a quick-flagged VPathRef distinct from content hash" in {
    val path = VPath("hybrid.txt")
    val srcQ = Task.sourceQuick("srcQ")(path)
    val srcC = Task.source("srcC")(path)
    for
      driver <- WorkflowTestDriver.init
      _      <- driver.vfs.writeBytes(path, Span.from("hello".getBytes), createFolders = true)
      qRef   <- driver.run(srcQ)
      cRef   <- driver.run(srcC)
    yield
      assert(qRef.quick)
      assert(!cRef.quick)
      assert(qRef.fingerprint != cRef.fingerprint)
  }

end SourceInvalidationTests
