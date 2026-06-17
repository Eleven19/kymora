package io.eleven19.kymora.workflow

import kyo.*

sealed trait Task[+A] derives CanEqual:
  def id: TaskId
  def version: TaskVersion

object Task:
  // Concrete kinds (Task.Cached, Task.Persistent) added in subsequent tasks (19, 21).
end Task
