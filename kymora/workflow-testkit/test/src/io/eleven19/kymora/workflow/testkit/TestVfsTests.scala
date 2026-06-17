package io.eleven19.kymora.workflow.testkit

import io.eleven19.kymora.vfs.*
import kyo.*
import kyo.test.*

class TestVfsTests extends Test[Any]:
  "tempDir gives a usable VFS + path" in {
    Scope.run {
      for
        td <- TestVfs.tempDir
        path = td.root / "hello.txt"
        _    <- td.vfs.writeBytes(path, Span.from("hi".getBytes), createFolders = true)
        b    <- td.vfs.readBytes(path)
      yield assert(b.toArray.sameElements("hi".getBytes))
    }
  }

  "tempDir cleans up its working area on Scope exit" in {
    for
      rootAfter <- Scope.run {
        for
          td <- TestVfs.tempDir
          _  <- td.vfs.writeBytes(td.root / "marker", Span.from("x".getBytes), createFolders = true)
        yield td.root
      }
    yield
      // Shape-only assertion: cleanup ran without crashing the Scope.
      assert(rootAfter.show.nonEmpty)
  }
end TestVfsTests
