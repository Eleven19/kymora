package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class AnyTaskTests extends Test[Any]:

  "AnyTask holds heterogeneous tasks without exposing Task wildcard syntax" in {
    val input  = Task.input("number")(1)
    val cached = Task.cached("text")("hello")
    val tasks  = Chunk(AnyTask(input), AnyTask(cached))

    assert(tasks.map(_.id) == Chunk(TaskId("number"), TaskId("text")))
  }

  "AnyTask preserves a path-dependent ResultType for recovery as Task" in {
    val any  = AnyTask(Task.cached("answer")(42))
    val task = any.asTask

    assert(task.id == TaskId("answer"))
  }
end AnyTaskTests
