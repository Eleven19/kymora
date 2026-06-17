package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.VPath
import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

/** Runtime coverage for `Task.Sources` — the multi-path source.
  *
  * Aggregate fingerprint is order-sensitive over the per-path
  * fingerprints (matches [[VPathRef.aggregateFingerprint]]), so reordering
  * the input varargs invalidates downstream caches. Per-path content
  * changes propagate the same way as the singular [[Task.Source]].
  */
class SourcesInvalidationTests extends Test[Any]:

  "Sources aggregate changes when any one of its paths' bytes change" in {
    val a = VPath("multi", "a")
    val b = VPath("multi", "b")
    val s = Task.sources("srcs")(a, b)
    for
      driver <- WorkflowTestDriver.init
      _      <- driver.vfs.writeBytes(a, Span.from("av1".getBytes), createFolders = true)
      _      <- driver.vfs.writeBytes(b, Span.from("bv1".getBytes), createFolders = true)
      r1     <- driver.run(s)
      _      <- driver.vfs.writeBytes(b, Span.from("bv2-different".getBytes), createFolders = true)
      r2     <- driver.run(s)
    yield
      assert(r1.size == 2)
      assert(r2.size == 2)
      assert(VPathRef.aggregateFingerprint(r1) != VPathRef.aggregateFingerprint(r2))
  }

  "Sources aggregate stable across runs when no bytes change" in {
    val a = VPath("stable", "a")
    val b = VPath("stable", "b")
    val s = Task.sources("srcs")(a, b)
    for
      driver <- WorkflowTestDriver.init
      _      <- driver.vfs.writeBytes(a, Span.from("aa".getBytes), createFolders = true)
      _      <- driver.vfs.writeBytes(b, Span.from("bb".getBytes), createFolders = true)
      r1     <- driver.run(s)
      r2     <- driver.run(s)
    yield assert(VPathRef.aggregateFingerprint(r1) == VPathRef.aggregateFingerprint(r2))
  }

  "Sources aggregate is order-sensitive (reordering paths changes fingerprint)" in {
    val a = VPath("order", "a")
    val b = VPath("order", "b")
    val ab = Task.sources("ab")(a, b)
    val ba = Task.sources("ba")(b, a)
    for
      driver <- WorkflowTestDriver.init
      _      <- driver.vfs.writeBytes(a, Span.from("alpha".getBytes), createFolders = true)
      _      <- driver.vfs.writeBytes(b, Span.from("beta".getBytes), createFolders = true)
      rAB    <- driver.run(ab)
      rBA    <- driver.run(ba)
    yield assert(VPathRef.aggregateFingerprint(rAB) != VPathRef.aggregateFingerprint(rBA))
  }

  "Empty Sources produces a stable, non-empty aggregate fingerprint" in {
    val s = Task.sources("empty")()
    for
      driver <- WorkflowTestDriver.init
      r1     <- driver.run(s)
      r2     <- driver.run(s)
    yield
      assert(r1.isEmpty)
      val fp1 = VPathRef.aggregateFingerprint(r1)
      val fp2 = VPathRef.aggregateFingerprint(r2)
      assert(fp1 == fp2)
      assert(fp1.value.nonEmpty)
  }

end SourcesInvalidationTests
