package io.eleven19.kymora.workflow

import kyo.*

/** Data-only description of a workflow task.
  *
  * A descriptor intentionally exposes task metadata without exposing the executable task body or result type. Use it
  * anywhere callers need to inspect, render, compare, or serialize the shape of a task graph.
  */
final case class TaskDescriptor(
    id: TaskId,
    kind: TaskDescriptor.Kind,
    deps: Chunk[TaskId],
    version: TaskVersion
) derives CanEqual

object TaskDescriptor:

    enum Kind derives CanEqual:
        case Source, Sources, Input, Cached, Persistent, Activity, Command
end TaskDescriptor
