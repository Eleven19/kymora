package io.eleven19.kymora.workflow.testkit

import kyo.*
import kyo.test.*

/** Base class for kymora-workflow test suites.
  *
  * Extends `kyo.test.Test[Any]` and adds a [[test]] helper that registers
  * a leaf with `.timeout(defaultTimeout)` already applied, so a wedged
  * test fails cleanly with a `[TIMEOUT]` marker instead of hanging the
  * JVM.
  *
  * Two registration styles coexist:
  *
  *   1. `test("name") { body }`           — applies [[defaultTimeout]]
  *   2. `"name" in { body }`              — raw kyo-test, NO timeout
  *   3. `"name".timeout(d) in { body }`   — explicit per-test override
  *
  * Note: WorkflowSpec cannot shadow the inherited `String.in` extension
  * because it is `inline` in `kyo.test.internal.TestBase` and therefore
  * final. The [[test]] helper is the WorkflowSpec convention; prefer it
  * for new suites.
  *
  * Override [[defaultTimeout]] in a subclass to widen or narrow:
  * {{{
  *   class MyLongRunningSpec extends WorkflowSpec:
  *     override val defaultTimeout: Duration = 10.minutes
  *     test("seeds 10k entries") { ... }
  * }}}
  */
abstract class WorkflowSpec extends Test[Any]:

  /** Per-test timeout applied to every [[test]] call in this suite. */
  def defaultTimeout: Duration = 3.minutes

  /** Register a leaf test with [[defaultTimeout]] applied.
    *
    * Equivalent to `name.timeout(defaultTimeout) in body`. To override
    * the timeout for one specific test, fall back to the raw kyo-test
    * decorator syntax: `"name".timeout(d) in { body }`.
    */
  inline def test(name: String)(
      inline body: kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope),
  )(using inline f: Frame): Unit =
    name.timeout(defaultTimeout).in(body)

end WorkflowSpec
