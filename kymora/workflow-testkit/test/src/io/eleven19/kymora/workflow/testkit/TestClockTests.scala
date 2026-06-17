package io.eleven19.kymora.workflow.testkit

import kyo.*
import kyo.test.*

class TestClockTests extends Test[Any]:
  "TestClock starts at a fixed default epoch by default" in {
    TestClock.run: _ =>
      Clock.now.map(now => assert(now == TestClock.DefaultEpoch))
  }
  "TestClock.advance moves time forward" in {
    TestClock.run: tc =>
      for
        _   <- tc.advance(60.seconds)
        now <- Clock.now
      yield assert(now == TestClock.DefaultEpoch + 60.seconds)
  }
  "TestClock.set jumps time to an absolute instant" in {
    TestClock.run: tc =>
      val target = Instant.parse("2030-12-31T23:59:59Z").getOrThrow
      for
        _   <- tc.set(target)
        now <- Clock.now
      yield assert(now == target)
  }
  "successive advances accumulate" in {
    TestClock.run: tc =>
      for
        _   <- tc.advance(10.seconds)
        _   <- tc.advance(5.seconds)
        now <- Clock.now
      yield assert(now == TestClock.DefaultEpoch + 15.seconds)
  }
end TestClockTests
