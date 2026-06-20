package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.*
import kyo.*
import kyo.test.*

class WorkflowPlanTests extends Test[Any]:

    "Workflow.inspectResult returns a static plan with kinds, deps, goals, and stable order" in {
        val source     = Task.source("source")(VPath.root / "input.txt")
        val sources    = Task.sources("sources")(VPath.root / "a.txt", VPath.root / "b.txt")
        val input      = Task.input("input")(1)
        val cached     = Task.cached("cached", TaskVersion.of(1, 2, 3))(source, input)((_, n) => n + 1)
        val persistent = Task.persistent("persistent")(sources)(refs => refs.size)
        val activity   = Task.activity("activity")(cached, persistent)((a, b) => a + b)
        val command    = Task.command("command")(activity)(value => value.toString)

        Workflow.inspectResult(AnyTask(command)) match
            case Result.Success(plan) =>
                assert(plan.goalIds == Chunk(TaskId("command")))
                assert(
                    plan.order == Chunk(
                        TaskId("source"),
                        TaskId("input"),
                        TaskId("cached"),
                        TaskId("sources"),
                        TaskId("persistent"),
                        TaskId("activity"),
                        TaskId("command")
                    )
                )
                assert(
                    plan.node(TaskId("source")) == Present(
                        TaskDescriptor(
                            TaskId("source"),
                            TaskDescriptor.Kind.Source,
                            Chunk.empty,
                            TaskVersion.v1
                        )
                    )
                )
                assert(
                    plan.node(TaskId("sources")) == Present(
                        TaskDescriptor(
                            TaskId("sources"),
                            TaskDescriptor.Kind.Sources,
                            Chunk.empty,
                            TaskVersion.v1
                        )
                    )
                )
                assert(
                    plan.node(TaskId("input")) == Present(
                        TaskDescriptor(
                            TaskId("input"),
                            TaskDescriptor.Kind.Input,
                            Chunk.empty,
                            TaskVersion.v1
                        )
                    )
                )
                assert(
                    plan.node(TaskId("cached")) == Present(
                        TaskDescriptor(
                            TaskId("cached"),
                            TaskDescriptor.Kind.Cached,
                            Chunk(TaskId("source"), TaskId("input")),
                            TaskVersion.of(1, 2, 3)
                        )
                    )
                )
                assert(
                    plan.node(TaskId("persistent")) == Present(
                        TaskDescriptor(
                            TaskId("persistent"),
                            TaskDescriptor.Kind.Persistent,
                            Chunk(TaskId("sources")),
                            TaskVersion.v1
                        )
                    )
                )
                assert(
                    plan.node(TaskId("activity")) == Present(
                        TaskDescriptor(
                            TaskId("activity"),
                            TaskDescriptor.Kind.Activity,
                            Chunk(TaskId("cached"), TaskId("persistent")),
                            TaskVersion.v1
                        )
                    )
                )
                assert(
                    plan.node(TaskId("command")) == Present(
                        TaskDescriptor(
                            TaskId("command"),
                            TaskDescriptor.Kind.Command,
                            Chunk(TaskId("activity")),
                            TaskVersion.v1
                        )
                    )
                )
                assert(plan.node(TaskId("missing")) == Absent)
            case Result.Failure(error) => assert(false, s"expected success, got failure: $error")
            case Result.Panic(t)       => assert(false, s"expected success, got panic: $t")
    }

    "Workflow.inspect preserves duplicate id validation through WorkflowException" in {
        val first  = Task.cached("duplicate")(1)
        val second = Task.cached("duplicate")(2)

        Workflow.inspectResult(AnyTask(first), AnyTask(second)) match
            case Result.Failure(WorkflowError.DuplicateTaskId(id, _)) => assert(id == TaskId("duplicate"))
            case other => assert(false, s"expected DuplicateTaskId, got: $other")

        try
            val _ = Workflow.inspect(AnyTask(first), AnyTask(second))
            assert(false, "expected WorkflowException")
        catch
            case e: Workflow.WorkflowException =>
                e.error match
                    case WorkflowError.DuplicateTaskId(id, _) => assert(id == TaskId("duplicate"))
                    case other                                => assert(false, s"expected DuplicateTaskId, got: $other")
    }
end WorkflowPlanTests
