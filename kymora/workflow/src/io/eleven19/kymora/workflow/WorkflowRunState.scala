package io.eleven19.kymora.workflow

import kyo.*

final case class WorkflowRunState(
    goals: Chunk[TaskId],
    tasks: Map[TaskId, WorkflowRunState.Task],
    completed: Boolean,
    durationMs: Long,
    hits: Int,
    misses: Int,
    failed: Int,
    events: Chunk[WorkflowEvent]
) derives CanEqual:

    import WorkflowRunState.*
    import WorkflowRunState.TaskStatus.*

    def applyEvent(event: WorkflowEvent): WorkflowRunState =
        val state = copy(events = events ++ Chunk(event))
        event match
            case WorkflowEvent.RunStarted(goals, _) =>
                val freshRun = state.copy(
                    goals = goals,
                    tasks = Map.empty,
                    completed = false,
                    durationMs = 0L,
                    hits = 0,
                    misses = 0,
                    failed = 0
                )
                goals.foldLeft(freshRun)((s, id) => s.ensureTask(id))

            case WorkflowEvent.RunCompleted(durationMs, hits, misses, failed) =>
                state.copy(
                    completed = true,
                    durationMs = durationMs,
                    hits = hits,
                    misses = misses,
                    failed = failed
                )

            case WorkflowEvent.TaskQueued(id) =>
                state.updateTask(id) { task =>
                    if TaskStatus.isTerminal(task.status) then task
                    else task.copy(status = Queued)
                }

            case WorkflowEvent.TaskStarted(id, deps, at) =>
                state.updateTask(id)(_.copy(status = Running, deps = Present(deps), startedAt = Present(at)))

            case WorkflowEvent.TaskCached(id, inputsHash) =>
                state.updateTask(id)(_.copy(status = Cached, inputsHash = Present(inputsHash)))

            case WorkflowEvent.TaskCompleted(id, valueHash, durationMs) =>
                state.updateTask(id)(
                    _.copy(status = Succeeded, valueHash = Present(valueHash), durationMs = Present(durationMs))
                )

            case WorkflowEvent.TaskFailed(id, message) =>
                state.updateTask(id)(_.copy(status = Failed, message = Present(message)))

            case WorkflowEvent.TaskCancelled(id, reason) =>
                state.updateTask(id)(_.copy(status = Cancelled, message = Present(reason)))

    private def ensureTask(id: TaskId): WorkflowRunState =
        if tasks.contains(id) then this
        else copy(tasks = tasks.updated(id, Task(id = id, status = Pending)))

    private def updateTask(id: TaskId)(f: Task => Task): WorkflowRunState =
        val task = tasks.getOrElse(id, Task(id = id, status = Pending))
        copy(tasks = tasks.updated(id, f(task)))
end WorkflowRunState

object WorkflowRunState:

    val empty: WorkflowRunState =
        WorkflowRunState(
            goals = Chunk.empty,
            tasks = Map.empty,
            completed = false,
            durationMs = 0L,
            hits = 0,
            misses = 0,
            failed = 0,
            events = Chunk.empty
        )

    final case class Task(
        id: TaskId,
        status: TaskStatus,
        deps: Maybe[Chunk[TaskId]] = Absent,
        startedAt: Maybe[java.time.Instant] = Absent,
        inputsHash: Maybe[Fingerprint] = Absent,
        valueHash: Maybe[Fingerprint] = Absent,
        durationMs: Maybe[Long] = Absent,
        message: Maybe[String] = Absent
    ) derives CanEqual

    enum TaskStatus derives CanEqual:
        case Pending, Queued, Running, Cached, Succeeded, Failed, Cancelled

    object TaskStatus:

        def isTerminal(status: TaskStatus): Boolean =
            status match
                case TaskStatus.Cached | TaskStatus.Succeeded | TaskStatus.Failed | TaskStatus.Cancelled => true
                case TaskStatus.Pending | TaskStatus.Queued | TaskStatus.Running                         => false
end WorkflowRunState
