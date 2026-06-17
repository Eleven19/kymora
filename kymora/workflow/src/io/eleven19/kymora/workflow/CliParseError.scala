package io.eleven19.kymora.workflow

import kyo.*

/** Errors raised while parsing the CLI surface (`runCli`) before any task
  * actually runs.
  *
  * Kept separate from [[WorkflowError]] so the CLI layer can report
  * usage-style failures without polluting the engine's error surface.
  *
  * `Schema` is **not** derived for the same reason as
  * [[WorkflowError]]: `UnknownCommand` and `MissingArgsParser` carry
  * `TaskId` (opaque), which the kyo-schema macro does not treat as a
  * serializable leaf.
  */
sealed trait CliParseError derives CanEqual
object CliParseError:
  /** A generic parse failure with the renderable usage banner attached. */
  final case class Failed(message: String, usage: String) extends CliParseError

  /** The first token did not match any registered task id.
    *
    * @param available
    *   The set of known task ids, suitable for use in a "did you mean…?"
    *   suggestion.
    */
  final case class UnknownCommand(token: String, available: Chunk[TaskId]) extends CliParseError

  /** The task exists but does not register a `CommandArgs` parser, so it
    * cannot be invoked from the CLI.
    */
  final case class MissingArgsParser(taskId: TaskId) extends CliParseError
end CliParseError
