package io.eleven19.kymora.workflow.internal

import io.eleven19.kymora.workflow.*
import kyo.*

private[workflow] object Planner:

    final case class ExecutionPlan(
        tasks: Map[TaskId, AnyTask],
        deps: Map[TaskId, Chunk[TaskId]],
        goalIds: Chunk[TaskId],
        order: Chunk[TaskId]
    )

    def build(goals: Chunk[AnyTask]): Result[WorkflowError, ExecutionPlan] =
        Graph.collect(goals.toSeq).map { tasks =>
            val deps = tasks.map((id, task) => id -> Graph.depIdsOf(task))
            ExecutionPlan(
                tasks = tasks,
                deps = deps,
                goalIds = goals.map(_.id),
                order = stableOrder(goals)
            )
        }
    end build

    def inspect(goals: Chunk[AnyTask]): Result[WorkflowError, WorkflowPlan] =
        build(goals).map { plan =>
            WorkflowPlan(
                nodes = plan.tasks.map((id, task) =>
                    id -> TaskDescriptor(
                        id = id,
                        kind = kindOf(task),
                        deps = plan.deps(id),
                        version = task.version
                    )
                ),
                goalIds = plan.goalIds,
                order = plan.order
            )
        }

    private def stableOrder(
        goals: Chunk[AnyTask]
    ): Chunk[TaskId] =
        val visited = scala.collection.mutable.Set.empty[TaskId]
        val result  = Chunk.newBuilder[TaskId]

        def visit(task: AnyTask): Unit =
            if visited.contains(task.id) then ()
            else
                Graph.depsOf(task).foreach(visit)
                val _ = visited.add(task.id)
                result.addOne(task.id)
        end visit

        goals.foreach(visit)
        result.result()
    end stableOrder

    private def kindOf(task: AnyTask): TaskDescriptor.Kind =
        task.unsafeTask match
            case _: Task.Source        => TaskDescriptor.Kind.Source
            case _: Task.Sources       => TaskDescriptor.Kind.Sources
            case _: Task.Input[?]      => TaskDescriptor.Kind.Input
            case _: Task.Cached[?]     => TaskDescriptor.Kind.Cached
            case _: Task.Persistent[?] => TaskDescriptor.Kind.Persistent
            case _: Task.Activity[?]   => TaskDescriptor.Kind.Activity
            case _: Task.Command[?]    => TaskDescriptor.Kind.Command
end Planner
