package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

class InputTests extends Test[Any]:
  "Task.input captures id and hashable" in {
    val i = Task.input[String]("scalaVersion")("3.8.4")
    assert(i.id == TaskId("scalaVersion"))
  }
  "Task.input evaluates the read effect on demand" in {
    var count = 0
    val i = Task.input[Int]("counter") {
      count += 1
      count
    }
    for
      driver <- WorkflowTestDriver.init
      r1     <- driver.run(i)
      r2     <- driver.run(i)
    yield
      assert(r1 == 1)
      assert(r2 == 2)
  }
  "Task.input prepends TaskScope" in {
    given TaskScope = TaskScope("kymora.workflow.jvm")
    val i           = Task.input[String]("scalaVersion")("3.8.4")
    assert(i.id == TaskId("kymora.workflow.jvm.scalaVersion"))
  }
end InputTests
