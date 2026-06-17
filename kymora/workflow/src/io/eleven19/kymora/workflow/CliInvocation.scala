package io.eleven19.kymora.workflow

import kyo.*

/** Raw CLI invocation handed to a [[Command]] body, wrapped in the
  * [[CommandContext]] passed to user code.
  *
  *   - `raw` — the unparsed argv tail for the command.
  *   - `parsed` — the engine-parsed args (typed in Phase 13 once `CommandArgs`
  *     derivation lands; loose `Maybe[Any]` for now so the carrier stays stable).
  */
final case class CliInvocation(raw: Chunk[String], parsed: Maybe[Any]):
  /** Re-parse `raw` under a different `Args` type.
    *
    * Phase 13 wires this through `CommandArgs[Args]`; until then it's a stub
    * that fails loudly so accidental callers don't silently get nonsense.
    */
  def as[Args]: Nothing =
    sys.error("CliInvocation.as[Args] not yet wired — see plan Phase 13 Task 50+")
end CliInvocation
