package io.eleven19.kymora.workflow.internal

import io.eleven19.kymora.workflow.*
import kyo.*

/** Engine-internal graph traversal + validation.
  *
  * Walks a forest of goal tasks, collecting every reachable task into a
  * `TaskId`-keyed map and surfacing duplicate-id collisions as
  * `WorkflowError.DuplicateTaskId`.
  *
  * Cycles are not detectable here. Our `Task` model is constructor-based:
  * dependencies are passed as already-constructed `Task` values, so a cycle
  * would require a task to depend on itself before it has been constructed,
  * which is impossible. `WorkflowError.CycleDetected` is reserved for future
  * expression-based or named-reference forms.
  */
private[workflow] object Graph:

  /** Reachable-tasks-by-id map (in arbitrary traversal order). */
  type Reachable = Map[TaskId, Task[?]]

  /** Walk `goals` → all reachable tasks. Detects duplicate ids (two distinct
    * `Task` instances claiming the same `TaskId`) and returns the FIRST such
    * collision as `WorkflowError.DuplicateTaskId`.
    */
  def collect(goals: Seq[Task[?]]): Either[WorkflowError, Reachable] =
    val seen  = scala.collection.mutable.LinkedHashMap.empty[TaskId, Task[?]]
    val stack = scala.collection.mutable.Stack.from(goals)

    while stack.nonEmpty do
      val t = stack.pop()
      seen.get(t.id) match
        case Some(existing) if !existing.eq(t) =>
          return Left(WorkflowError.DuplicateTaskId(
            t.id,
            Chunk(kindOf(existing), kindOf(t)),
          ))
        case Some(_) =>
          () // already seen, same instance; skip
        case None =>
          val _ = seen.update(t.id, t)
          depsOf(t).foreach(d => { val _ = stack.push(d) })
    end while

    Right(seen.toMap)
  end collect

  private def depsOf(t: Task[?]): Seq[Task[?]] = t match
    case c: Task.Cached[?]     => c.deps
    case p: Task.Persistent[?] => p.deps
    case k: Task.Command[?]    => k.deps
    case _: Task.Source        => Nil
    case _: Task.Input[?]      => Nil

  private def kindOf(t: Task[?]): String = t match
    case _: Task.Cached[?]     => "Task.Cached"
    case _: Task.Persistent[?] => "Task.Persistent"
    case _: Task.Command[?]    => "Task.Command"
    case _: Task.Source        => "Source"
    case _: Task.Input[?]      => "Input"

end Graph
