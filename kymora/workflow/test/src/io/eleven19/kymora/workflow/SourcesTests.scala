package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.VPath
import kyo.*
import kyo.test.*

class SourcesTests extends Test[Any]:
  "Task.sources records the paths and uses content hashing by default" in {
    val a = VPath("repo", "a")
    val b = VPath("repo", "b")
    val s = Task.sources("srcs")(a, b)
    assert(s.id == TaskId("srcs"))
    assert(s.paths == Chunk(a, b))
    assert(!s.quick)
  }
  "Task.sourcesQuick sets the quick flag on every path" in {
    val s = Task.sourcesQuick("srcs")(VPath("p1"), VPath("p2"))
    assert(s.quick)
    assert(s.paths.size == 2)
  }
  "Task.sources prepends TaskScope" in {
    given TaskScope = TaskScope("kymora.vfs.jvm")
    val s           = Task.sources("sources")(VPath("repo", "src"))
    assert(s.id == TaskId("kymora.vfs.jvm.sources"))
  }
  "Task.sources accepts an empty path list" in {
    val s = Task.sources("empty")()
    assert(s.paths.isEmpty)
    assert(!s.quick)
  }
end SourcesTests
