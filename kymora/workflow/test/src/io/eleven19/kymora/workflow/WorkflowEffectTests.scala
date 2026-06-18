package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.*
import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

class WorkflowEffectTests extends Test[Any]:

    "PR example: Workflow.handle runs Workflow.run with an explicit Runtime" in {
        val compile = Task.cached("compile") {
            "compiled"
        }

        for
            backend <- Vfs.inMemory.init
            runtime = Workflow.Runtime(
                Workflow.Config.default,
                backend,
                VPath("cache"),
                Observer.NoOp
            )
            result <- Workflow.handle(runtime) {
                Workflow.run(compile)
            }
        yield assert(result == "compiled")
    }

    "Task.apply runs through Workflow" in {
        val goal = Task.init("answer")(42)
        for
            driver <- WorkflowTestDriver.init
            result <- Workflow.handle(driver.runtime)(goal())
        yield assert(result == 42)
    }

    "PR example: task apply shorthand runs inside Workflow" in {
        val compile = Task.cached("compile") {
            "compiled"
        }

        for
            driver <- WorkflowTestDriver.init
            result <- Workflow.handle(driver.runtime) {
                compile()
            }
        yield assert(result == "compiled")
    }

    "Task bodies can use Workflow.dest and VFS path syntax" in {
        val goal = Task.init("write") {
            for
                dest <- Workflow.dest
                file = dest / "out.txt"
                _ <- file.write("hello")
            yield file.show
        }
        val sealedFile = VPath("cache") / "write.dest" / "out.txt"
        val tmpFile    = VPath("cache") / "write.dest.tmp" / "out.txt"
        for
            driver <- WorkflowTestDriver.init
            path   <- driver.run(goal)
            text   <- driver.vfs.read(sealedFile)
            tmp    <- driver.vfs.exists(tmpFile)
        yield
            assert(path == tmpFile.show)
            assert(text == "hello")
            assert(!tmp)
    }

    "PR example: cached task writes a report under Workflow.dest" in {
        val writeReport = Task.cached("report") {
            for
                dest <- Workflow.dest
                file = dest / "report.txt"
                _ <- file.write("generated report")
            yield file
        }

        val sealedFile = VPath("cache") / "report.dest" / "report.txt"
        for
            driver <- WorkflowTestDriver.init
            file   <- driver.run(writeReport)
            text   <- driver.vfs.read(sealedFile)
        yield
            assert(file == VPath("cache") / "report.dest.tmp" / "report.txt")
            assert(text == "generated report")
    }

    "Persistent tasks decode cached values and preserve .dest when invalidated" in {
        var revision = 1
        val input    = Task.input("revision")(revision)
        val goal = Task.persistent("persist")(input) { _ =>
            for
                dest <- Workflow.dest
                marker = dest / "marker.txt"
                vfs    <- Vfs.get
                exists <- vfs.exists(marker)
                value <-
                    if exists then marker.read
                    else marker.write("first").map(_ => "first")
            yield value
        }
        val marker = VPath("cache") / "persist.dest" / "marker.txt"
        for
            driver <- WorkflowTestDriver.init
            first  <- driver.run(goal)
            _      <- driver.vfs.write(marker, "second")
            second <- driver.run(goal)
            _       = revision = 2
            third  <- driver.run(goal)
        yield
            assert(first == "first")
            assert(second == "first")
            assert(third == "second")
    }

    "PR example: persistent task reuses its Workflow.dest marker after invalidation" in {
        var revision = 1
        val input    = Task.input("stateful-revision")(revision)
        val stateful = Task.persistent("stateful")(input) { _ =>
            for
                dest <- Workflow.dest
                marker = dest / "marker.txt"
                vfs    <- Vfs.get
                exists <- vfs.exists(marker)
                value  <- if exists then marker.read else marker.write("first").map(_ => "first")
            yield value
        }

        val marker = VPath("cache") / "stateful.dest" / "marker.txt"
        for
            driver <- WorkflowTestDriver.init
            first  <- driver.run(stateful)
            _      <- driver.vfs.write(marker, "second")
            second <- driver.run(stateful)
            _       = revision = 2
            third  <- driver.run(stateful)
        yield
            assert(first == "first")
            assert(second == "first")
            assert(third == "second")
    }

    "Task bodies can fail with public WorkflowError" in {
        val error = WorkflowError.InvalidTaskId("bad id", "contains spaces")
        val goal  = Task.init[Int]("bad")(Abort.fail(error))
        for
            driver  <- WorkflowTestDriver.init
            attempt <- Abort.run[WorkflowError](driver.run(goal))
        yield assert(attempt == Result.fail(error))
    }

    "PR example: public WorkflowError failures are recoverable" in {
        val invalid = Task.cached[Int]("invalid") {
            Abort.fail(WorkflowError.InvalidTaskId("bad id", "contains spaces"))
        }

        for
            driver <- WorkflowTestDriver.init
            attempt <- Workflow.handle(driver.runtime) {
                Abort.run[WorkflowError](invalid())
            }
        yield assert(attempt == Result.fail(WorkflowError.InvalidTaskId("bad id", "contains spaces")))
    }

    "Throwable task failures remain recoverable as WorkflowError" in {
        val goal = Task.init("boom") {
            throw new RuntimeException("boom")
            0
        }
        for
            driver  <- WorkflowTestDriver.init
            attempt <- Abort.run[WorkflowError](driver.run(goal))
        yield
            assert(attempt.isFailure)
            assert(attempt.fold(_ => false, _.isInstanceOf[WorkflowError.TaskFailed], _ => false))
    }
end WorkflowEffectTests
