package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.VPath
import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

/** Closes #5 §3 and #11: [[Task.Source]] content-hashes through the workflow runtime VFS, and dependent cached tasks
  * invalidate across separate [[Workflow.run]] invocations when source bytes change.
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

  "Dependent Task.Cached re-runs across invocations when source bytes change" in {
    val path  = VPath("input.txt")
    val src   = Task.source("src")(path)
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    val compiled = Task.cached("compile")(src) { ref =>
      s"${ref.fingerprint.value}:${count.incrementAndGet()}"
    }

    for
      driver <- WorkflowTestDriver.init
      _      <- driver.vfs.writeBytes(path, Span.from("v1".getBytes), createFolders = true)
      first  <- driver.run(compiled)
      _      <- driver.vfs.writeBytes(path, Span.from("v2".getBytes), createFolders = true)
      second <- driver.run(compiled)
      events <- driver.events
    yield
      assert(first.endsWith(":1"))
      assert(second.endsWith(":2"))
      assert(first != second)
      assert(count.get() == 2)
      assert(events.collect { case e: WorkflowEvent.TaskCompleted if e.id == compiled.id => e }.size == 2)
      assert(events.collect { case e: WorkflowEvent.TaskCached if e.id == compiled.id => e }.isEmpty)
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
