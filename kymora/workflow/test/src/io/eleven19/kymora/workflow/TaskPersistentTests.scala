package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class TaskPersistentTests extends Test[Any]:
  "Task.persistent constructs a Task.Persistent" in {
    val t = Task.persistent("p")(42)
    assert(t.isInstanceOf[Task.Persistent[?]])
  }
  "Task.persistent honours TaskScope" in {
    given TaskScope = TaskScope("kymora.vfs")
    val t           = Task.persistent("p")(0)
    assert(t.id == TaskId("kymora.vfs.p"))
  }
  "Task.persistent (1 dep) records the dep" in {
    val dep = Task.init("d")(0)
    val t   = Task.persistent("p")(dep) { x => x + 1 }
    val p   = t.asInstanceOf[Task.Persistent[Int]]
    assert(p.deps.size == 1)
    assert(p.deps.head.eq(dep))
  }
end TaskPersistentTests
