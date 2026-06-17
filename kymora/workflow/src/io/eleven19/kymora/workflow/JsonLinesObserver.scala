package io.eleven19.kymora.workflow

import kyo.*

/** Emits one JSON object per line. Sink defaults to stdout. */
object JsonLinesObserver extends Observer:
  /** Hand-rolled JSON encoder. Avoids kyo-schema's opaque-type derivation
    * limitation (TaskId, Fingerprint are opaque String aliases).
    */
  def toJson(event: WorkflowEvent): String =
    def s(x: String): String = "\"" + x.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    def arr[A](xs: Chunk[A])(f: A => String): String =
      xs.iterator.map(f).mkString("[", ",", "]")
    event match
      case WorkflowEvent.RunStarted(goals, at) =>
        s"""{"type":"RunStarted","goals":${arr(goals)(id => s(id.value))},"at":${s(at.toString)}}"""
      case WorkflowEvent.RunCompleted(d, h, m, f) =>
        s"""{"type":"RunCompleted","durationMs":$d,"hits":$h,"misses":$m,"failed":$f}"""
      case WorkflowEvent.TaskQueued(id) =>
        s"""{"type":"TaskQueued","id":${s(id.value)}}"""
      case WorkflowEvent.TaskStarted(id, deps, at) =>
        s"""{"type":"TaskStarted","id":${s(id.value)},"deps":${arr(deps)(d => s(d.value))},"at":${s(at.toString)}}"""
      case WorkflowEvent.TaskCached(id, ih) =>
        s"""{"type":"TaskCached","id":${s(id.value)},"inputsHash":${s(ih.value)}}"""
      case WorkflowEvent.TaskCompleted(id, vh, d) =>
        s"""{"type":"TaskCompleted","id":${s(id.value)},"valueHash":${s(vh.value)},"durationMs":$d}"""
      case WorkflowEvent.TaskFailed(id, msg) =>
        s"""{"type":"TaskFailed","id":${s(id.value)},"message":${s(msg)}}"""
      case WorkflowEvent.TaskCancelled(id, reason) =>
        s"""{"type":"TaskCancelled","id":${s(id.value)},"reason":${s(reason)}}"""
    end match
  end toJson

  override def onEvent(event: WorkflowEvent): Unit < Async =
    Console.printLine(toJson(event))
end JsonLinesObserver
