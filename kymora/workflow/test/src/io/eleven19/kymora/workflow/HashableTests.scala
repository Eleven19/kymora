package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

final case class Person(name: String, age: Int) derives Schema, CanEqual

class HashableTests extends Test[Any]:
  "Hashable derived from Schema for case class" in {
    val a = Person("alice", 30)
    val b = Person("alice", 30)
    assert(summon[Hashable[Person]].hash(a) == summon[Hashable[Person]].hash(b))
  }
  "Hashable distinguishes different values" in {
    val a = Person("alice", 30)
    val b = Person("alice", 31)
    assert(summon[Hashable[Person]].hash(a) != summon[Hashable[Person]].hash(b))
  }
end HashableTests
