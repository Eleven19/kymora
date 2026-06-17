package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.internal.Validation
import kyo.*
import kyo.test.*

class TaskIdTests extends Test[Any]:
  "TaskId.parse accepts valid id" in {
    val r = TaskId.parse("kymora.vfs.jvm.compile")
    assert(r.isSuccess && r.toMaybe.contains(TaskId.unsafe("kymora.vfs.jvm.compile")))
  }
  "TaskId.parse rejects slash" in {
    val r = TaskId.parse("foo/bar")
    assert(r == Result.fail(Validation.Reason.ContainsSlash))
  }
  "TaskId.value round-trips" in {
    val id = TaskId.unsafe("foo.bar")
    assert(id.value == "foo.bar")
  }
  "TaskId.segments splits on dot" in {
    val id = TaskId.unsafe("foo.bar.baz")
    assert(id.segments == List("foo", "bar", "baz"))
  }
  "TaskId Schema round-trips through Json" in {
    val id = TaskId("foo.bar")
    val s  = summon[Schema[TaskId]].encodeString[Json](id)
    val r  = summon[Schema[TaskId]].decodeString[Json](s)
    assert(r == Result.succeed(id))
  }
end TaskIdTests
