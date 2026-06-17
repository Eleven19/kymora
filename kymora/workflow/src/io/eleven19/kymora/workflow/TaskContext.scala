package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.VPath
import kyo.*

/** Engine-injected context passed to Task / Command bodies.
  *
  * `dest` is the `.dest/` working directory the engine pre-created for this
  * task body (relevant for Task.Cached and Task.Persistent file-output paths).
  *
  * For now `emit` is a stub (`Any => Unit`); the real WorkflowEvent emit
  * lands in Phase 10. Kept here so the body signature is stable.
  */
final case class TaskContext(
    dest: VPath,
    emit: Any => Unit, // TODO replaced with WorkflowEvent => Unit in Phase 10
    clock: Clock,
)
