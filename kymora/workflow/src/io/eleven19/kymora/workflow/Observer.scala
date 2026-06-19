package io.eleven19.kymora.workflow

import kyo.*

/** Consumer of the engine's [[WorkflowEvent]] stream.
  *
  * Minimal contract: `onEvent(event)` accepts a single event. The higher-level `openSession()` method returns a
  * `Scope`-bound [[Observer.Session]] that the engine can use to bracket a run's observer state (e.g. a JSON file
  * handle to close, a console live-progress display to tear down).
  *
  * Concrete observers: `ConsoleObserver`, `JsonLinesObserver`.
  */
trait Observer:
    /** Called by the engine for each emitted [[WorkflowEvent]]. */
    def onEvent(event: WorkflowEvent): Unit < Async = ()

    /** Acquire an [[Observer.Session]] for one workflow run.
      *
      * The default implementation returns a thin pass-through session that delegates `onEvent` back to this observer
      * and has a no-op `close`. Observers that own real resources (open file, live display) should override this to
      * register cleanup via `Scope.ensure`.
      */
    def openSession(): Observer.Session < (Async & Scope) =
        val self = this
        Sync.defer(new Observer.Session:
            def onEvent(event: WorkflowEvent): Unit < Async = self.onEvent(event)
            def close(): Unit < Async                       = ())
end Observer

object Observer:

    /** Per-run handle returned by [[Observer.openSession]].
      *
      * The engine calls `onEvent` for every emitted [[WorkflowEvent]] during a run and `close` once at the end.
      * Resource-owning observers typically register `close` with `Scope.ensure` so that an early failure still tears
      * them down.
      */
    trait Session:
        def onEvent(event: WorkflowEvent): Unit < Async
        def close(): Unit < Async
    end Session

    /** Discards every event. Useful for benchmarks and silent runs. */
    object NoOp extends Observer
end Observer
