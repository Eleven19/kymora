package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class TaskTraitTests extends Test[Any]:
  "Task is a sealed trait" in {
    assert(classOf[Task[?]].isInterface)
  }
end TaskTraitTests
