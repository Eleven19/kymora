package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class TaskVersionTests extends Test[Any]:
  "TaskVersion.v1 is 1.0.0" in {
    assert(TaskVersion.v1.render == "1.0.0")
  }
  "bumpMinor resets patch" in {
    assert(TaskVersion(1, 2, 5).bumpMinor == TaskVersion(1, 3, 0))
  }
  "bumpMajor resets minor and patch" in {
    assert(TaskVersion(2, 4, 7).bumpMajor == TaskVersion(3, 0, 0))
  }
  "TaskVersion.parse round-trips its render" in {
    val v = TaskVersion(2, 4, 7)
    assert(TaskVersion.parse(v.render) == Result.succeed(v))
  }
  "TaskVersion.parse fails on bad input" in {
    assert(TaskVersion.parse("2.4").isFailure)
    assert(TaskVersion.parse("a.b.c").isFailure)
    assert(TaskVersion.parse("2.4.7.1").isFailure)
  }
  "Schema encodes to single string" in {
    val v = TaskVersion(1, 2, 3)
    val s = summon[Schema[TaskVersion]].encodeString[Json](v)
    assert(s == "\"1.2.3\"")
  }
  "Schema decodes from single string" in {
    val r = summon[Schema[TaskVersion]].decodeString[Json]("\"1.2.3\"")
    assert(r == Result.succeed(TaskVersion(1, 2, 3)))
  }
end TaskVersionTests
