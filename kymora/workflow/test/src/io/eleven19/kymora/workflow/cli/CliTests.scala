package io.eleven19.kymora.workflow.cli

import caseapp.*
import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

/** End-to-end coverage for [[Cli.runWith]] — the bridge between case-app
  * argument parsing and parameterized [[Task]] invocation.
  *
  * Each test wires a parameterized command via `Task.command[A, P]`, then
  * runs it through `Cli.runWith` with a `Seq[String]` of CLI tokens, and
  * verifies either the resulting value (success path) or that
  * [[CliParseError.Failed]] is raised (parse-failure path).
  */
class CliTests extends Test[Any]:
  "Cli.runWith parses tokens and invokes task" in {
    val task: ServeArgs => Task.Command[String] =
      Task.command[String, ServeArgs]("serve") { a => s"port=${a.port}" }
    val invocation =
      Abort.recover[CliParseError](e => s"err:$e")(Cli.runWith(task, Seq("--port", "8080")))
    for
      driver <- WorkflowTestDriver.init
      result <- Env.run(driver.config)(invocation)
    yield assert(result == "port=8080")
  }
  "Cli.runWith uses argument defaults when tokens are empty" in {
    val task: ServeArgs => Task.Command[String] =
      Task.command[String, ServeArgs]("serve") { a => s"port=${a.port}" }
    val invocation =
      Abort.recover[CliParseError](e => s"err:$e")(Cli.runWith(task, Seq.empty))
    for
      driver <- WorkflowTestDriver.init
      result <- Env.run(driver.config)(invocation)
    yield assert(result == "port=4")
  }
  "Cli.runWith reports CliParseError.Failed on bad input" in {
    val task: ServeArgs => Task.Command[String] =
      Task.command[String, ServeArgs]("serve") { a => s"port=${a.port}" }
    val invocation =
      Abort.recover[CliParseError] {
        case CliParseError.Failed(_, _)         => "failed"
        case CliParseError.UnknownCommand(_, _) => "unknown"
      }(Cli.runWith(task, Seq("--port", "not-a-number")))
    for
      driver <- WorkflowTestDriver.init
      out    <- Env.run(driver.config)(invocation)
    yield assert(out == "failed")
  }
end CliTests

/** CLI args case class for the tests. Lives at file-top level so case-app's
  * `Parser` / `Help` derivations apply at compile time and stay outside the
  * test class body (derivations on nested classes can hit the strict-equality
  * compiler at unexpected places).
  */
final case class ServeArgs(@Name("port") port: Int = 4) derives CanEqual

object ServeArgs:
  given parser: caseapp.core.parser.Parser[ServeArgs] = caseapp.core.parser.Parser.derive
  given help: caseapp.core.help.Help[ServeArgs] = caseapp.core.help.Help.derive
