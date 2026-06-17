package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.cli.*
import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

// Pin our `Command` alias to defend against the `kyo.*` re-export shadow.
import io.eleven19.kymora.workflow.Command

final case class TestArgs(port: Int)
object TestArgs:
  given parser: CommandArgs[TestArgs] = CommandArgs.from(
    parse = tokens =>
      tokens match
        case Seq("--port", v) =>
          v.toIntOption match
            case Some(p) => Result.succeed(TestArgs(p))
            case None    => Result.fail(CliParseError.Failed(s"bad: $v", "--port <int>"))
        case _ => Result.fail(CliParseError.Failed("missing --port", "--port <int>")),
    usage = "--port <int>",
  )

class RunCliTests extends Test[Any]:
  "Workflow.runCli passes parsed args to body" in {
    val cmd = Command.cli[TestArgs, String]("serve") { args => s"port=${args.port}" }
    for
      driver <- WorkflowTestDriver.init
      result <- Env.run(driver.config)(Workflow.runCli(cmd, Seq("--port", "8080")))
    yield assert(result == "port=8080")
  }
  "Workflow.runCli surfaces CliParseError on bad input" in {
    val cmd = Command.cli[TestArgs, String]("serve") { _ => "ok" }
    for
      driver  <- WorkflowTestDriver.init
      attempt <- Abort.run[CliParseError | WorkflowError](
                   Env.run(driver.config)(Workflow.runCli(cmd, Seq("--bogus"))),
                 )
    yield assert(attempt.isFailure)
  }
end RunCliTests
