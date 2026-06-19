package io.eleven19.kymora.workflow.cli

import caseapp.core.Error as CaseAppError
import caseapp.core.help.Help
import caseapp.core.parser.Parser
import io.eleven19.kymora.workflow.*
import kyo.*

/** Bridges [[caseapp]] argument parsing to parameterized [[Task]] invocation.
  *
  * Usage shape:
  *
  *   1. Build a parameterized task:
  *      `val serve: Args => Task.Command[String] = Task.command[String, Args]("serve") { ... }`
  *   2. Parse + invoke from `Seq[String]` tokens: `Cli.runWith(serve, args)` inside `Workflow.handle(runtime)(...)`.
  *
  * Errors:
  *   - Parse failures from case-app surface as [[CliParseError.Failed]] on the Abort channel.
  *   - Task execution errors surface as the normal [[WorkflowError]] channel.
  */
object Cli:

    /** Parses `tokens` into a `P` via the implicit [[caseapp.core.parser.Parser]] and runs `task(p)` through
      * [[Workflow.run]].
      *
      * The Abort channel carries both [[WorkflowError]] (task execution) and [[CliParseError]] (token parsing) —
      * callers can recover from each independently.
      */
    def runWith[P, A](task: P => Task[A], tokens: Seq[String])(using
        parser: Parser[P],
        help: Help[P],
        frame: Frame
    ): A < (Async & Workflow & Abort[WorkflowError | CliParseError]) =
        parser.parse(tokens) match
            case Right((p, _remainingArgs)) =>
                Workflow.run(task(p))
            case Left(err: CaseAppError) =>
                Abort.fail[CliParseError](
                    CliParseError.Failed(err.message, help.help)
                )

    /** Parses `tokens` into a `P`, dispatches to the matching sub-command, and runs it through [[Workflow.run]].
      *
      * The first token selects the command by `name`; remaining tokens are parsed via that command's `Parser`. Unknown
      * command names surface as [[CliParseError.UnknownCommand]] with the registered command names suggested via
      * [[TaskId]] for consistency with engine error reporting.
      */
    def runCommands[A](
        commands: Cli.Command[?, A]*
    )(tokens: Seq[String])(using
        frame: Frame
    ): A < (Async & Workflow & Abort[WorkflowError | CliParseError]) =
        val cmds = commands.toVector
        tokens.toList match
            case Nil =>
                Abort.fail[CliParseError](
                    CliParseError.Failed("no command given", renderUsage(cmds))
                )
            case name :: rest =>
                cmds.find(_.name == name) match
                    case Some(cmd) => cmd.runRest(rest)
                    case None =>
                        Abort.fail[CliParseError](
                            CliParseError.UnknownCommand(
                                name,
                                Chunk.from(cmds.map(c => TaskId.unsafe(c.name)))
                            )
                        )

    /** A named sub-command bundle: name + parameterized task + parser/help. */
    final class Command[P, A] private[cli] (
        val name: String,
        task: P => Task[A],
        parser: Parser[P],
        help: Help[P]
    ):

        private[cli] def runRest(rest: List[String])(using
            Frame
        ): A < (Async & Workflow & Abort[WorkflowError | CliParseError]) =
            runWith(task, rest)(using parser, help, summon[Frame])
    end Command

    object Command:

        /** Build a CLI command bundle. */
        def apply[P, A](name: String, task: P => Task[A])(using
            parser: Parser[P],
            help: Help[P]
        ): Cli.Command[P, A] =
            new Cli.Command(name, task, parser, help)
    end Command

    private def renderUsage[A](cmds: Seq[Cli.Command[?, A]]): String =
        if cmds.isEmpty then ""
        else cmds.map(c => s"  ${c.name}").mkString("commands:\n", "\n", "")
end Cli
