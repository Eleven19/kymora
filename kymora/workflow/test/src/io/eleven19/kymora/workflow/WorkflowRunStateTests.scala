package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class WorkflowRunStateTests extends Test[Any]:

    private def project(events: WorkflowEvent*): WorkflowRunState =
        events.foldLeft(WorkflowRunState.empty)((state, event) => state.applyEvent(event))

    "projects started queued running and succeeded task state" in {
        val goal      = TaskId("package")
        val dep       = TaskId("compile")
        val startedAt = java.time.Instant.parse("2026-06-18T12:00:00Z")
        val valueHash = Fingerprint.unsafe("blake3:value")
        val events = Chunk(
            WorkflowEvent.RunStarted(Chunk(goal), startedAt),
            WorkflowEvent.TaskQueued(goal),
            WorkflowEvent.TaskStarted(goal, Chunk(dep), startedAt),
            WorkflowEvent.TaskCompleted(goal, valueHash, durationMs = 42L)
        )

        val state = events.foldLeft(WorkflowRunState.empty)((state, event) => state.applyEvent(event))

        assert(state.goals == Chunk(goal))
        assert(state.completed == false)
        assert(state.events == events)
        assert(
            state.tasks(goal) == WorkflowRunState.Task(
                id = goal,
                status = WorkflowRunState.TaskStatus.Succeeded,
                deps = Present(Chunk(dep)),
                startedAt = Present(startedAt),
                valueHash = Present(valueHash),
                durationMs = Present(42L)
            )
        )
    }

    "projects cache hits as terminal cached tasks with inputs hash" in {
        val id         = TaskId("compile")
        val inputsHash = Fingerprint.unsafe("blake3:inputs")

        val state = project(
            WorkflowEvent.TaskQueued(id),
            WorkflowEvent.TaskCached(id, inputsHash),
            WorkflowEvent.TaskQueued(id)
        )

        assert(state.tasks(id).status == WorkflowRunState.TaskStatus.Cached)
        assert(state.tasks(id).deps == Absent)
        assert(state.tasks(id).inputsHash == Present(inputsHash))
    }

    "projects deps as unknown until a task starts" in {
        val id        = TaskId("compile")
        val dep       = TaskId("source")
        val startedAt = java.time.Instant.parse("2026-06-18T12:00:00Z")

        val queued  = project(WorkflowEvent.TaskQueued(id))
        val cached  = queued.applyEvent(WorkflowEvent.TaskCached(id, Fingerprint.unsafe("blake3:inputs")))
        val running = cached.applyEvent(WorkflowEvent.TaskStarted(id, Chunk(dep), startedAt))

        assert(queued.tasks(id).deps == Absent)
        assert(cached.tasks(id).deps == Absent)
        assert(running.tasks(id).deps == Present(Chunk(dep)))
    }

    "projects failure and cancellation status with messages" in {
        val failed    = TaskId("failed")
        val cancelled = TaskId("cancelled")

        val state = project(
            WorkflowEvent.TaskFailed(failed, "boom"),
            WorkflowEvent.TaskCancelled(cancelled, "blocked by failed")
        )

        assert(state.tasks(failed).status == WorkflowRunState.TaskStatus.Failed)
        assert(state.tasks(failed).message == Present("boom"))
        assert(state.tasks(cancelled).status == WorkflowRunState.TaskStatus.Cancelled)
        assert(state.tasks(cancelled).message == Present("blocked by failed"))
    }

    "projects run completion aggregate fields" in {
        val state = project(
            WorkflowEvent.RunStarted(Chunk(TaskId("goal")), java.time.Instant.parse("2026-06-18T12:00:00Z")),
            WorkflowEvent.RunCompleted(durationMs = 120L, hits = 2, misses = 3, failed = 1)
        )

        assert(state.completed == true)
        assert(state.durationMs == 120L)
        assert(state.hits == 2)
        assert(state.misses == 3)
        assert(state.failed == 1)
    }

    "projects a new run as fresh run state while preserving event history" in {
        val firstGoal  = TaskId("first")
        val secondGoal = TaskId("second")
        val firstStart = WorkflowEvent.RunStarted(Chunk(firstGoal), java.time.Instant.parse("2026-06-18T12:00:00Z"))
        val secondStart = WorkflowEvent.RunStarted(
            Chunk(secondGoal),
            java.time.Instant.parse("2026-06-18T12:01:00Z")
        )

        val state = project(
            firstStart,
            WorkflowEvent.TaskQueued(firstGoal),
            WorkflowEvent.TaskCompleted(firstGoal, Fingerprint.unsafe("blake3:first"), durationMs = 10L),
            WorkflowEvent.RunCompleted(durationMs = 100L, hits = 1, misses = 2, failed = 3),
            secondStart
        )

        assert(state.goals == Chunk(secondGoal))
        assert(state.tasks == Map(secondGoal -> WorkflowRunState.Task(secondGoal, WorkflowRunState.TaskStatus.Pending)))
        assert(state.completed == false)
        assert(state.durationMs == 0L)
        assert(state.hits == 0)
        assert(state.misses == 0)
        assert(state.failed == 0)
        assert(
            state.events == Chunk(
                firstStart,
                WorkflowEvent.TaskQueued(firstGoal),
                WorkflowEvent.TaskCompleted(firstGoal, Fingerprint.unsafe("blake3:first"), durationMs = 10L),
                WorkflowEvent.RunCompleted(durationMs = 100L, hits = 1, misses = 2, failed = 3),
                secondStart
            )
        )
    }
end WorkflowRunStateTests
