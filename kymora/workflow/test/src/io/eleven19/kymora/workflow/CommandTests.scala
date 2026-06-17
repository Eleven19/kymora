package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

// `kyo.*` re-exports a `Command` of its own that would shadow our package's
// `Command` alias under a wildcard import, so pin it back explicitly here.
import io.eleven19.kymora.workflow.Command

class CommandTests extends Test[Any]:
  "Command.init records id, version, deps" in {
    val dep = Task.init("dep")(1)
    val c   = Command.init("run")(dep) { x => () }
    assert(c.id == TaskId("run"))
    assert(c.deps.size == 1)
    assert(c.deps.head.eq(dep))
  }
  "CliInvocation.raw is a Chunk" in {
    val i = CliInvocation(Chunk("--port", "8080"), Maybe.empty)
    assert(i.raw(0) == "--port")
    assert(i.raw.size == 2)
  }
end CommandTests
