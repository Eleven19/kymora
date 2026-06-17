package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.cli.CommandArgs
import kyo.*

/** Raw CLI invocation handed to a [[Command]] body, wrapped in the
  * [[CommandContext]] passed to user code.
  *
  *   - `raw` — the unparsed argv tail for the command.
  *   - `parsed` — the engine-parsed args (typed in Phase 13 once `CommandArgs`
  *     derivation lands; loose `Maybe[Any]` for now so the carrier stays
  *     stable).
  */
final case class CliInvocation(raw: Chunk[String], parsed: Maybe[Any]):
  /** Re-parse `raw` under a different `Args` type using the supplied
    * [[CommandArgs]] typeclass instance.
    */
  def as[Args](using p: CommandArgs[Args]): Result[CliParseError, Args] =
    p.parse(raw.toSeq)
end CliInvocation
