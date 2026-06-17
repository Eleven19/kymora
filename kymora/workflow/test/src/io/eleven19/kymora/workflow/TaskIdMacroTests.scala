package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*
import scala.compiletime.testing.typeCheckErrors

class TaskIdMacroTests extends Test[Any]:
  "TaskId(\"good.id\") compiles" in {
    val id = TaskId("kymora.vfs.compile")
    assert(id.value == "kymora.vfs.compile")
  }
  "TaskId(\"with slash\") fails to compile" in {
    val errs = typeCheckErrors("io.eleven19.kymora.workflow.TaskId(\"foo/bar\")")
    assert(errs.exists(_.message.contains("ContainsSlash")))
  }
  "TaskId(\"reserved\") fails to compile" in {
    val errs = typeCheckErrors("io.eleven19.kymora.workflow.TaskId(\"kymora.__workflow__\")")
    assert(errs.exists(_.message.contains("ReservedSegment")))
  }
end TaskIdMacroTests
