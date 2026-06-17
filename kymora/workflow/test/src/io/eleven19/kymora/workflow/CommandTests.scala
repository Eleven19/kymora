package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.cli.CommandArgs
import kyo.*
import kyo.test.*

class CommandTests extends Test[Any]:
  "Task.command records id, version, deps" in {
    val dep = Task.init("dep")(1)
    val c   = Task.command("run")(dep) { x => () }
    assert(c.id == TaskId("run"))
    assert(c.deps.size == 1)
    assert(c.deps.head.eq(dep))
  }
  "CliInvocation.raw is a Chunk" in {
    val i = CliInvocation(Chunk("--port", "8080"), Maybe.empty)
    assert(i.raw(0) == "--port")
    assert(i.raw.size == 2)
  }
  "Task.command leaf returns a Command" in {
    val c = Task.command("a")(42)
    assert(c.isInstanceOf[Task.Command[?]])
    assert(c.id == TaskId("a"))
  }
  "Task.command (1 dep) records the dep" in {
    val dep = Task.init("dep")(0)
    val c   = Task.command("c")(dep) { x => x + 1 }
    assert(c.deps.size == 1)
  }
  "Task.command works under import kyo.*" in {
    import kyo.*
    val c = Task.command("under-kyo")(42)
    assert(c.id == TaskId("under-kyo"))
  }
  "Task.cli builds a Command with parsed args path" in {
    given CommandArgs[Int] = CommandArgs.from(
      tokens =>
        tokens match
          case Seq(v) =>
            v.toIntOption.fold(Result.fail(CliParseError.Failed("bad", "<int>")))(i =>
              Result.succeed(i),
            )
          case _ => Result.fail(CliParseError.Failed("missing", "<int>")),
      "<int>",
    )
    val c = Task.cli[Int, String]("svc") { i => i.toString }
    assert(c.isInstanceOf[Task.Command[?]])
  }
end CommandTests
