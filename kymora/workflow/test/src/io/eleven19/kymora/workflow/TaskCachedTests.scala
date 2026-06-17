package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class TaskCachedTests extends Test[Any]:
  "Task.init (leaf) yields a Task with the supplied id" in {
    val t = Task.init("foo")(42)
    assert(t.id == TaskId("foo"))
    assert(t.version == TaskVersion.v1)
  }
  "Task.init prepends the ambient TaskScope" in {
    given TaskScope = TaskScope("kymora.vfs.jvm")
    val t           = Task.init("compile")(0)
    assert(t.id == TaskId("kymora.vfs.jvm.compile"))
  }
  "Task.init accepts an explicit version" in {
    val t = Task.init("foo", version = TaskVersion(2, 1, 0))(0)
    assert(t.version == TaskVersion(2, 1, 0))
  }
  "Task.init (1 dep) records the dep" in {
    val dep    = Task.init("dep")(0)
    val t      = Task.init("foo")(dep) { x => x + 1 }
    val cached = t.asInstanceOf[Task.Cached[Int]]
    assert(cached.deps.size == 1)
    assert(cached.deps.head eq dep)
  }
  "Task.init (6 deps) records all deps in order" in {
    val ds = (1 to 6).map(i => Task.init(s"d$i")(i)).toVector
    val t  = Task.init("six")(ds(0), ds(1), ds(2), ds(3), ds(4), ds(5)) {
      (a, b, c, d, e, f) => a + b + c + d + e + f
    }
    val cached = t.asInstanceOf[Task.Cached[Int]]
    assert(cached.deps.size == 6)
    (0 until 6).foreach(i => assert(cached.deps(i).eq(ds(i))))
  }
end TaskCachedTests
