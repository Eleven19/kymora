package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.internal.Validation
import kyo.*
import kyo.test.*
import scala.compiletime.testing.typeCheckErrors

class WorkflowScopeTests extends Test[Any]:
  "scope prepends prefix to inner tasks" in {
    val t = Workflow.scope("kymora.vfs"):
      Task.init("compile")(0)
    assert(t.id == TaskId("kymora.vfs.compile"))
  }
  "nested scopes concatenate" in {
    val t = Workflow.scope("a"):
      Workflow.scope("b"):
        Task.init("c")(0)
    assert(t.id == TaskId("a.b.c"))
  }
  "scope with bad literal fails to compile" in {
    val errs = typeCheckErrors("""io.eleven19.kymora.workflow.Workflow.scope("foo/bar")(0)""")
    assert(errs.nonEmpty)
    assert(errs.exists(_.message.contains("ContainsSlash")))
  }
  "scopeWith returns Result.fail on bad runtime input" in {
    val r = Workflow.scopeWith("foo/bar")(Task.init("x")(0))
    assert(r.isFailure)
  }
end WorkflowScopeTests
