package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.VPath

/** File/directory input. Re-evaluated each run (path content rehashed via VFS).
  * Contributes to dependents' cache keys via the embedded fingerprint.
  *
  * Engine constants:
  *   version = TaskVersion.v1
  *   bodyHash = blake3("source:v1")
  *
  * The actual `Source` class is defined inside [[Task]] so it can extend the
  * sealed `Task` trait. This file exposes the top-level `Source` alias and
  * companion-style smart constructors `Source.init` / `Source.quick`.
  */
type Source = Task.Source

object Source:
  def init(id: String)(path: VPath)(using scope: TaskScope): Source =
    Task.Source.init(id)(path)
  def quick(id: String)(path: VPath)(using scope: TaskScope): Source =
    Task.Source.quick(id)(path)
end Source
