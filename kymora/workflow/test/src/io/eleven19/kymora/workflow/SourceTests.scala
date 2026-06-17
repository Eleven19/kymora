package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.VPath
import kyo.*
import kyo.test.*

class SourceTests extends Test[Any]:
  "Task.source records the path and uses content hashing by default" in {
    val p = VPath("repo", "src")
    val s = Task.source("src")(p)
    assert(s.id == TaskId("src"))
    assert(!s.quick)
  }
  "Task.sourceQuick sets the quick flag" in {
    val p = VPath("repo", "src")
    val s = Task.sourceQuick("src")(p)
    assert(s.quick)
  }
  "Task.source prepends TaskScope" in {
    given TaskScope = TaskScope("kymora.vfs.jvm")
    val p           = VPath("repo", "src")
    val s           = Task.source("sources")(p)
    assert(s.id == TaskId("kymora.vfs.jvm.sources"))
  }
end SourceTests
