package io.eleven19.kymora.workflow

import kyo.*

/** Pretty-prints workflow events to `Console`.
  *
  * Uses Kyo's `Console` effect rather than direct `System.out`, so test suites can capture output and non-JVM platforms
  * can provide their own console.
  */
object ConsoleObserver extends Observer:

    /** Format a single event as a one-line stdout-ready string. */
    def format(event: WorkflowEvent): String = event match
        case WorkflowEvent.RunStarted(goals, at)     => s"[$at] RUN  start  goals=${goals.size}"
        case WorkflowEvent.RunCompleted(d, h, m, f)  => s"RUN  done   ${d}ms hits=$h misses=$m failed=$f"
        case WorkflowEvent.TaskQueued(id)            => s"TASK queue  ${id.value}"
        case WorkflowEvent.TaskStarted(id, deps, at) => s"[$at] TASK start  ${id.value} (deps=${deps.size})"
        case WorkflowEvent.TaskCached(id, ih)        => s"TASK CACHED ${id.value} inputs=${ih.value}"
        case WorkflowEvent.TaskCompleted(id, vh, d)  => s"TASK DONE   ${id.value} ${d}ms value=${vh.value}"
        case WorkflowEvent.TaskFailed(id, msg)       => s"TASK FAILED ${id.value} — $msg"
        case WorkflowEvent.TaskCancelled(id, reason) => s"TASK CANCEL ${id.value} — $reason"

    override def onEvent(event: WorkflowEvent): Unit < Async =
        Console.printLine(format(event))
end ConsoleObserver
