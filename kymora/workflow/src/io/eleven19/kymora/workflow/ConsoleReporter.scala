package io.eleven19.kymora.workflow

import kyo.*

/** Pretty-prints events to stdout. */
object ConsoleReporter extends Reporter:
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
    Sync.defer(java.lang.System.out.println(format(event)))
end ConsoleReporter
