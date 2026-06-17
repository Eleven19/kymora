package io.eleven19.kymora.workflow.testkit

import kyo.*

/** Controllable [[Clock]] fake for tests.
  *
  * `TestClock` exposes the same control surface as Kyo's
  * [[Clock.TimeControl]] — [[set]] to jump to an absolute instant and
  * [[advance]] to move time forward — and is installed in the ambient
  * [[Clock]] context for the duration of the [[run]] block. Inside that
  * block, any call to `Clock.now` reads the controlled time.
  *
  * Use it via [[TestClock.run]]:
  * {{{
  *   TestClock.run { tc =>
  *     for
  *       _   <- tc.advance(60.seconds)
  *       now <- Clock.now
  *     yield now
  *   }
  * }}}
  *
  * A fresh `TestClock` starts at [[TestClock.DefaultEpoch]].
  */
final class TestClock private (control: Clock.TimeControl):

  /** Jumps to an absolute instant. */
  def set(instant: Instant): Unit < Async =
    control.set(instant)

  /** Moves time forward by the given duration. */
  def advance(d: Duration): Unit < Async =
    control.advance(d)

end TestClock

object TestClock:

  /** The instant a fresh `TestClock` starts at. */
  val DefaultEpoch: Instant =
    Instant.parse("2026-01-01T00:00:00Z").getOrThrow

  /** Runs `f` with a [[TestClock]] installed as the ambient [[Clock]].
    *
    * Time starts at [[DefaultEpoch]] and only moves when `f` calls
    * [[TestClock.set]] or [[TestClock.advance]].
    *
    * @param f
    *   A function taking the [[TestClock]] and returning an effect.
    */
  def run[A, S](f: TestClock => A < S)(using Frame): A < (Async & S) =
    Clock.withTimeControl: control =>
      for
        _      <- control.set(DefaultEpoch)
        result <- f(new TestClock(control))
      yield result

end TestClock
