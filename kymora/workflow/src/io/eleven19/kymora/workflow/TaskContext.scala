package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.VPath
import kyo.*

/** Engine-injected context passed to Task / Command bodies.
  *
  * `dest` is the engine-managed working directory for this task body. Cached tasks receive a fresh `.dest.tmp/` staging
  * directory that is sealed into `.dest/` on success; persistent tasks receive the retained `.dest/` directory
  * directly.
  *
  * Single-field case class so additional engine-provided context can be threaded through without changing every body
  * signature. Bodies that need time, randomness, or event emission should reach for Kyo's effects (`Clock`, `Random`,
  * `Emit`) directly rather than expect them on this record.
  */
final case class TaskContext(dest: VPath)
