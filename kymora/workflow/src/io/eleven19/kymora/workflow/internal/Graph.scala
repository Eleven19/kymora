package io.eleven19.kymora.workflow.internal

import io.eleven19.kymora.workflow.*
import kyo.*

/** Engine-internal graph traversal + validation.
  *
  * Walks a forest of goal tasks, collecting every reachable task into a `TaskId`-keyed map and surfacing duplicate-id
  * collisions as `WorkflowError.DuplicateTaskId`.
  *
  * Cycles are not detectable here. Our `Task` model is constructor-based: dependencies are passed as
  * already-constructed `Task` values, so a cycle would require a task to depend on itself before it has been
  * constructed, which is impossible. `WorkflowError.CycleDetected` is reserved for future expression-based or
  * named-reference forms.
  */
private[workflow] object Graph:

    /** Reachable-tasks-by-id map (in arbitrary traversal order). */
    type Reachable = Map[TaskId, AnyTask]

    /** Walk `goals` → all reachable tasks. Detects duplicate ids (two distinct `Task` instances claiming the same
      * `TaskId`) and returns the FIRST such collision as `WorkflowError.DuplicateTaskId`.
      */
    def collect(goals: Seq[AnyTask]): Result[WorkflowError, Reachable] =
        val seen  = scala.collection.mutable.LinkedHashMap.empty[TaskId, AnyTask]
        val stack = scala.collection.mutable.Stack.from(goals)

        while stack.nonEmpty do
            val t = stack.pop()
            seen.get(t.id) match
                case Some(existing) if !existing.unsafeTask.eq(t.unsafeTask) =>
                    return Result.fail(
                        WorkflowError.DuplicateTaskId(
                            t.id,
                            Chunk(kindOf(existing), kindOf(t))
                        )
                    )
                case Some(_) =>
                    () // already seen, same instance; skip
                case None =>
                    val _ = seen.update(t.id, t)
                    depsOf(t).foreach { d =>
                        val _ = stack.push(d)
                    }
        end while

        Result.succeed(seen.toMap)
    end collect

    private[workflow] def depsOf(t: AnyTask): Seq[AnyTask] = t.unsafeTask match
        case c: Task.Cached[?]     => c.deps
        case p: Task.Persistent[?] => p.deps
        case a: Task.Activity[?]   => a.deps
        case k: Task.Command[?]    => k.deps
        case _: Task.Source        => Nil
        case _: Task.Sources       => Nil
        case _: Task.Input[?]      => Nil

    /** Dependency task ids for `t`, preserving declaration order. */
    private[workflow] def depIdsOf(t: AnyTask): Chunk[TaskId] =
        Chunk.from(depsOf(t).map(_.id))

    private def kindOf(t: AnyTask): String = t.unsafeTask match
        case _: Task.Cached[?]     => "Task.Cached"
        case _: Task.Persistent[?] => "Task.Persistent"
        case _: Task.Activity[?]   => "Task.Activity"
        case _: Task.Command[?]    => "Task.Command"
        case _: Task.Source        => "Source"
        case _: Task.Sources       => "Sources"
        case _: Task.Input[?]      => "Input"

end Graph
