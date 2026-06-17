package io.eleven19.kymora.examples.smilebuild

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

class SmileBuildTests extends Test[Any]:
  "Build.core compile task id is namespaced under core scope" in {
    assert(Build.core.compile.id == TaskId("smile.core.compile"))
  }
  "Build.app compile depends on Build.core.compile (transitively)" in {
    for
      driver <- WorkflowTestDriver.init
      _      <- Env.run(driver.config)(driver.run(Build.app.compile))
      events <- driver.events
    yield
      val completed = events.collect { case e: WorkflowEvent.TaskCompleted => e.id }
      assert(completed.contains(TaskId("smile.core.compile")))
      assert(completed.contains(TaskId("smile.app.compile")))
  }
  "Build.app.jar runs end-to-end" in {
    for
      driver <- WorkflowTestDriver.init
      result <- Env.run(driver.config)(driver.run(Build.app.jar))
    yield assert(result.contains("app"))
  }
end SmileBuildTests
