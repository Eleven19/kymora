package io.eleven19.kymora.workflow.cli

import io.eleven19.kymora.workflow.*
import kyo.*
import kyo.test.*

class CommandArgsTests extends Test[Any]:
  "CommandArgs.unit accepts empty tokens" in {
    val r = summon[CommandArgs[Unit]].parse(Seq.empty)
    assert(r.isSuccess)
  }
  "CommandArgs.unit accepts tokens but doesn't consume them" in {
    val r = summon[CommandArgs[Unit]].parse(Seq("--whatever"))
    assert(r.isSuccess) // Unit args silently ignore
  }
  "CommandArgs.from manual factory parses tokens" in {
    final case class MyArgs(port: Int) derives CanEqual
    val parser: CommandArgs[MyArgs] = CommandArgs.from(
      parse = tokens =>
        tokens match
          case Seq("--port", v) =>
            v.toIntOption match
              case Some(p) => Result.succeed(MyArgs(p))
              case None    => Result.fail(CliParseError.Failed(s"bad port: $v", "--port <int>"))
          case _ => Result.fail(CliParseError.Failed("missing --port", "--port <int>")),
      usage = "--port <int>",
    )
    val r = parser.parse(Seq("--port", "8080"))
    assert(r == Result.succeed(MyArgs(8080)))
  }
end CommandArgsTests
