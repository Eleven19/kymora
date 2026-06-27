package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.testkit.CollectingObserver
import kyo.*
import kyo.test.*

class WorkflowTelemetryTests extends Test[Any]:

    "fromObserver forwards published events to the observer" in {
        val event = WorkflowEvent.TaskQueued(TaskId("compile"))

        for
            observer <- CollectingObserver.init
            telemetry = WorkflowTelemetry.fromObserver(observer)
            _      <- telemetry.publish(event)
            events <- observer.events
        yield assert(events == Chunk(event))
    }

    "NoOp swallows published events" in {
        for _ <- WorkflowTelemetry.NoOp.publish(WorkflowEvent.TaskQueued(TaskId("compile")))
        yield assert(true)
    }

    "live fans out published events to two subscribers" in {
        val first  = WorkflowEvent.TaskQueued(TaskId("compile"))
        val second = WorkflowEvent.TaskFailed(TaskId("test"), "boom")

        Scope.run {
            for
                telemetry  <- WorkflowTelemetry.live(bufferSize = 8)
                left       <- telemetry.subscribe(bufferSize = 8)
                right      <- telemetry.subscribe(bufferSize = 8)
                _          <- telemetry.publish(first)
                _          <- telemetry.publish(second)
                leftSeen   <- left.takeExactly(2)
                rightSeen  <- right.takeExactly(2)
            yield assert(leftSeen == Chunk(first, second) && rightSeen == Chunk(first, second))
        }
    }

    "live exposes a read-only subscription handle" in {
        val event = WorkflowEvent.TaskQueued(TaskId("subscribe-api"))

        Scope.run {
            for
                telemetry    <- WorkflowTelemetry.live(bufferSize = 8)
                subscription <- telemetry.subscribe(bufferSize = 8)
                _            <- telemetry.publish(event)
                seen         <- subscription.take
            yield assert(seen == event)
        }
    }

    "live prunes closed subscriptions without blocking live subscriptions" in {
        val event = WorkflowEvent.TaskQueued(TaskId("live-subscription"))

        Scope.run {
            for
                telemetry <- WorkflowTelemetry.live(bufferSize = 8)
                _         <- Scope.run(telemetry.subscribe(bufferSize = 8))
                live      <- telemetry.subscribe(bufferSize = 8)
                _         <- telemetry.publish(event)
                seen      <- live.take
            yield assert(seen == event)
        }
    }

    "live projects events into snapshot and state signal" in {
        val goal      = TaskId("package")
        val startedAt = java.time.Instant.parse("2026-06-18T12:00:00Z")
        val valueHash = Fingerprint.unsafe("blake3:value")

        Scope.run {
            for
                telemetry <- WorkflowTelemetry.live(bufferSize = 8)
                signal    <- telemetry.state
                _         <- telemetry.publish(WorkflowEvent.RunStarted(Chunk(goal), startedAt))
                _         <- telemetry.publish(WorkflowEvent.TaskCompleted(goal, valueHash, durationMs = 42L))
                snapshot  <- telemetry.snapshot
                current   <- signal.current
            yield
                assert(snapshot == current)
                assert(snapshot.goals == Chunk(goal))
                assert(snapshot.tasks(goal).status == WorkflowRunState.TaskStatus.Succeeded)
                assert(snapshot.tasks(goal).valueHash == Present(valueHash))
                assert(snapshot.tasks(goal).durationMs == Present(42L))
        }
    }

    "live snapshots include run bracketing and cache aggregates from real workflow execution" in {
        val goal = Task.init("compile")(42)

        Scope.run {
            for
                telemetry <- WorkflowTelemetry.live(bufferSize = 32)
                vfs       <- io.eleven19.kymora.vfs.Vfs.inMemory.init
                runtime = Workflow.Runtime(vfs = vfs, telemetryOverride = Maybe(telemetry))
                first       <- Workflow.handle(runtime)(Workflow.run(goal))
                firstState  <- telemetry.snapshot
                second      <- Workflow.handle(runtime)(Workflow.run(goal))
                secondState <- telemetry.snapshot
            yield
                assert(first == 42)
                assert(firstState.goals == Chunk(TaskId("compile")))
                assert(firstState.completed)
                assert(firstState.hits == 0)
                assert(firstState.misses == 1)
                assert(firstState.failed == 0)
                assert(second == 42)
                assert(secondState.goals == Chunk(TaskId("compile")))
                assert(secondState.completed)
                assert(secondState.hits == 1)
                assert(secondState.misses == 0)
                assert(secondState.failed == 0)
        }
    }

    "live keeps subscription and snapshot event order aligned for concurrent publishers" in {
        val events = Chunk.from((0 until 64).map(i => WorkflowEvent.TaskQueued(TaskId.unsafe(s"task-$i"))))

        Scope.run {
            for
                telemetry    <- WorkflowTelemetry.live(bufferSize = 128)
                subscription <- telemetry.subscribe(bufferSize = 128)
                _            <- Async.foreach(events, concurrency = events.size)(telemetry.publish)
                seen         <- subscription.takeExactly(events.size)
                snapshot     <- telemetry.snapshot
            yield
                assert(seen == snapshot.events)
                assert(seen.toSet == events.toSet)
        }
    }

    "publish after live scope shutdown is a whole-publish no-op" in {
        val event = WorkflowEvent.TaskQueued(TaskId("after-close"))

        for
            telemetry <- Scope.run(WorkflowTelemetry.live(bufferSize = 8))
            _         <- telemetry.publish(event)
            snapshot  <- telemetry.snapshot
        yield assert(snapshot == WorkflowRunState.empty)
    }

    "publish racing live scope shutdown completes" in {
        val events = Chunk.from((0 until 256).map(i => WorkflowEvent.TaskQueued(TaskId.unsafe(s"racing-$i"))))

        for
            fiber <- Scope.run {
                for
                    telemetry <- WorkflowTelemetry.live(bufferSize = 16)
                    fiber     <- Fiber.initUnscoped(Async.foreach(events, concurrency = 64)(telemetry.publish))
                yield fiber
            }
            result <- Abort.run[Timeout](Async.timeout(2.seconds)(fiber.get))
        yield assert(result.isSuccess)
    }

end WorkflowTelemetryTests
