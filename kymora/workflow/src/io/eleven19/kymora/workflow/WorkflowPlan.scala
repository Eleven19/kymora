package io.eleven19.kymora.workflow

import kyo.*

final case class WorkflowPlan(
    nodes: Map[TaskId, TaskDescriptor],
    goalIds: Chunk[TaskId],
    order: Chunk[TaskId]
) derives CanEqual:

    def node(id: TaskId): Maybe[TaskDescriptor] =
        nodes.get(id) match
            case Some(node) => Present(node)
            case None       => Absent
end WorkflowPlan
