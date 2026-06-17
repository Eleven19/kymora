package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.VPath
import kyo.*

/** Engine-injected context passed to Task / Command bodies.
  *
  * `dest` is the `.dest/` working directory the engine pre-created for this
  * task body (relevant for Task.Cached and Task.Persistent file-output paths).
  *
  * Single-field case class so additional engine-provided context can be
  * threaded through without changing every body signature. Bodies that need
  * time, randomness, or event emission should reach for Kyo's effects
  * (`Clock`, `Random`, `Emit`) directly rather than expect them on this
  * record.
  */
final case class TaskContext(dest: VPath)
