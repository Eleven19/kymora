package io.eleven19.kymora.workflow

import kyo.*

/** Errors raised while parsing the CLI surface before any task actually runs.
  *
  * Kept separate from [[WorkflowError]] so the CLI layer can report
  * usage-style failures without polluting the engine's error surface.
  *
  * `Schema` is derived (see [[WorkflowError]] for the same explanation): the
  * `TaskId` companion provides an explicit `given Schema[TaskId]`, so the
  * kyo-schema 1.0.0-RC2 macro can auto-derive for variants that carry it.
  */
sealed trait CliParseError derives CanEqual, Schema
object CliParseError:
  /** A generic parse failure with the renderable usage banner attached.
    *
    * Used by [[io.eleven19.kymora.workflow.cli.Cli]] to wrap case-app's
    * `caseapp.core.Error` value into a typed Kyo error channel.
    */
  final case class Failed(message: String, usage: String) extends CliParseError

  /** The first token did not match any registered task id.
    *
    * @param available
    *   The set of known task ids, suitable for use in a "did you mean…?"
    *   suggestion.
    */
  final case class UnknownCommand(token: String, available: Chunk[TaskId]) extends CliParseError
end CliParseError
