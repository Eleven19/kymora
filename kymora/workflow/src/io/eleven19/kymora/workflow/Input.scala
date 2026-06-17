package io.eleven19.kymora.workflow

import kyo.*

/** Pure-value input. Re-evaluated each run; contributes to dependents' cache
  * keys via the hash of the read value (not stored as a blob).
  *
  * Engine constants:
  *   version = TaskVersion.v1
  *   bodyHash = blake3("input:v1")
  *
  * The actual `Input` class is defined inside [[Task]] so it can extend the
  * sealed `Task` trait. This file exposes the top-level `Input` alias and the
  * companion-style smart constructor `Input.init`.
  */
type Input[A] = Task.Input[A]

object Input:
  def init[A](id: String)(read: => A < (Async & Abort[Throwable]))(using
      scope: TaskScope,
      h: Hashable[A],
  ): Input[A] =
    Task.Input.init[A](id)(read)
end Input
