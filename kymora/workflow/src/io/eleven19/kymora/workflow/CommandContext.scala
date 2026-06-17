package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.VPath
import kyo.*

/** Engine-injected context for a [[Command]] body.
  *
  * Like [[TaskContext]], but also carries the CLI invocation that triggered
  * the run. `emit` is the same WorkflowEvent-stub as `TaskContext.emit` and
  * gets the real `WorkflowEvent => Unit` signature in Phase 10.
  */
final case class CommandContext(
    args: CliInvocation,
    dest: VPath,
    emit: Any => Unit, // TODO replaced with WorkflowEvent => Unit in Phase 10
    clock: Clock,
)
