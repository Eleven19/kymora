package io.eleven19.kymora.workflow

import kyo.*

/** Consumer of the engine's [[WorkflowEvent]] stream.
  *
  * Minimal contract: `onEvent(event)` accepts a single event. The
  * higher-level `openSession()` method returns a [[Scope]]-bound [[Reporter.Session]]
  * that the engine can use to bracket a run's reporter state (e.g. a JSON file
  * handle to close, a console live-progress display to tear down).
  *
  * Concrete reporters (`ConsoleReporter`, `JsonLinesReporter`) land in Task 40.
  */
trait Reporter:
  /** Called by the engine for each emitted [[WorkflowEvent]]. */
  def onEvent(event: WorkflowEvent): Unit < Async = ()

  /** Acquire a [[Reporter.Session]] for one workflow run.
    *
    * The default implementation returns a thin pass-through session that
    * delegates `onEvent` back to this reporter and has a no-op `close`.
    * Reporters that own real resources (open file, live display) should
    * override this to register cleanup via [[Scope.ensure]].
    */
  def openSession(): Reporter.Session < (Async & Scope) =
    val self = this
    Sync.defer(new Reporter.Session:
      def onEvent(event: WorkflowEvent): Unit < Async = self.onEvent(event)
      def close(): Unit < Async                       = ()
    )
end Reporter

object Reporter:
  /** Per-run handle returned by [[Reporter.openSession]].
    *
    * The engine calls `onEvent` for every emitted [[WorkflowEvent]] during a
    * run and `close` once at the end. Resource-owning reporters typically
    * register `close` with [[Scope.ensure]] so that an early failure still
    * tears them down.
    */
  trait Session:
    def onEvent(event: WorkflowEvent): Unit < Async
    def close(): Unit < Async
  end Session

  /** Discards every event. Useful for benchmarks and silent runs. */
  object NoOp extends Reporter
end Reporter
