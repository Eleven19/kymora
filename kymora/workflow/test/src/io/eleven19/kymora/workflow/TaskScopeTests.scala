package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*
import scala.compiletime.testing.typeCheckErrors

class TaskScopeTests extends Test[Any]:
  "Root scope is empty" in {
    assert(TaskScope.Root.value == "")
  }
  "qualify on root returns the suffix" in {
    val s = TaskScope.Root.qualify("compile")
    assert(s.value == "compile")
  }
  "qualify on a non-root prefixes with dot" in {
    val s = TaskScope("kymora.vfs.jvm").qualify("compile")
    assert(s.value == "kymora.vfs.jvm.compile")
  }
  "TaskScope.parse rejects bad strings" in {
    assert(TaskScope.parse("foo/bar").isFailure)
  }
  "TaskScope(\"foo/bar\") fails to compile" in {
    val errs = typeCheckErrors("io.eleven19.kymora.workflow.TaskScope(\"foo/bar\")")
    assert(errs.exists(_.message.contains("ContainsSlash")))
  }
end TaskScopeTests
