package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.VPath
import kyo.*
import kyo.test.*

class SourceTests extends Test[Any]:
  "Source.init records the path and uses content hashing by default" in {
    val p = VPath("repo", "src")
    val s = Source.init("src")(p)
    assert(s.id == TaskId("src"))
    assert(!s.quick)
  }
  "Source.quick sets the quick flag" in {
    val p = VPath("repo", "src")
    val s = Source.quick("src")(p)
    assert(s.quick)
  }
  "Source.init prepends TaskScope" in {
    given TaskScope = TaskScope("kymora.vfs.jvm")
    val p           = VPath("repo", "src")
    val s           = Source.init("sources")(p)
    assert(s.id == TaskId("kymora.vfs.jvm.sources"))
  }
end SourceTests
