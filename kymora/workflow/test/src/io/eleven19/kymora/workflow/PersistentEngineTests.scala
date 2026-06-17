package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

class PersistentEngineTests extends Test[Any]:
  "Task.persistent leaf executes its body and returns the value" in {
    val goal = Task.persistent("p")(42)
    for
      driver <- WorkflowTestDriver.init
      result <- Env.run(driver.config)(driver.run(goal))
    yield assert(result == 42)
  }
  "Task.persistent (1 dep) chains through scheduler" in {
    val dep  = Task.init("dep")(10)
    val goal = Task.persistent("p")(dep) { x => x + 1 }
    for
      driver <- WorkflowTestDriver.init
      result <- Env.run(driver.config)(driver.run(goal))
    yield assert(result == 11)
  }
end PersistentEngineTests
