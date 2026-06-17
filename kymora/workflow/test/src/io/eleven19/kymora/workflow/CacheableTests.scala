package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class CacheableTests extends Test[Any]:
  "Cacheable round-trips with Json codec" in {
    given Codec = Json()
    val a       = Person("alice", 30)
    val bytes   = summon[Cacheable[Person]].encode(a)
    val back    = summon[Cacheable[Person]].decode(bytes)
    assert(back == Result.succeed(a))
  }
  "Cacheable round-trips with Protobuf codec" in {
    given Codec = Protobuf()
    val a       = Person("alice", 30)
    val bytes   = summon[Cacheable[Person]].encode(a)
    val back    = summon[Cacheable[Person]].decode(bytes)
    assert(back == Result.succeed(a))
  }
  "Cacheable surfaces DecodeError on malformed input" in {
    given Codec = Json()
    val r       = summon[Cacheable[Person]].decode(Chunk.from("not json".getBytes))
    assert(r.isFailure)
  }
end CacheableTests
