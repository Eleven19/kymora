package io.eleven19.kymora.examples.smilebuild

import io.eleven19.kymora.vfs.{Vfs, VPath}
import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

class SmileBuildTests extends Test[Any]:

  /** Each module's `sources` task is a [[Task.Sources]] pointing at
    * `repo/<name>/src` AND `repo/<name>/resources`. The engine reads each
    * path via the ambient `Env[Vfs]`, so tests seed both files before
    * running the build graph. */
  private def seedSources(driver: WorkflowTestDriver): Unit < (Async & Abort[Throwable]) =
    val modules = Seq(Build.core, Build.app)
    Kyo.foreach(Chunk.from(modules))(m =>
      Kyo.foreach(Chunk("src", "resources"))(leaf =>
        driver.vfs.writeBytes(
          VPath("repo", m.name, leaf),
          Span.from(s"$leaf for ${m.name}".getBytes),
          createFolders = true,
        ),
      ),
    ).unit

  "Build.core compile task id is namespaced under core scope" in {
    assert(Build.core.compile.id == TaskId("smile.core.compile"))
  }
  "Build.app compile depends on Build.core.compile (transitively)" in {
    for
      driver <- WorkflowTestDriver.init
      _      <- seedSources(driver)
      _      <- driver.run(Build.app.compile)
      events <- driver.events
    yield
      val completed = events.collect { case e: WorkflowEvent.TaskCompleted => e.id }
      assert(completed.contains(TaskId("smile.core.compile")))
      assert(completed.contains(TaskId("smile.app.compile")))
  }
  "Build.app.jar runs end-to-end" in {
    for
      driver <- WorkflowTestDriver.init
      _      <- seedSources(driver)
      result <- driver.run(Build.app.jar)
    yield assert(result.contains("app"))
  }
end SmileBuildTests
