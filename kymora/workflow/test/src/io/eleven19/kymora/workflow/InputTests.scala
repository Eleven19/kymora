package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class InputTests extends Test[Any]:
  "Input.init captures id and hashable" in {
    val i = Input.init[String]("scalaVersion")("3.8.4")
    assert(i.id == TaskId("scalaVersion"))
  }
  "Input.init evaluates the read effect on demand" in {
    var count = 0
    val i = Input.init[Int]("counter") {
      count += 1
      count
    }
    for
      r1 <- i.read()
      r2 <- i.read()
    yield
      assert(r1 == 1)
      assert(r2 == 2)
  }
  "Input.init prepends TaskScope" in {
    given TaskScope = TaskScope("kymora.workflow.jvm")
    val i           = Input.init[String]("scalaVersion")("3.8.4")
    assert(i.id == TaskId("kymora.workflow.jvm.scalaVersion"))
  }
end InputTests
