package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

class TaskArityTests extends Test[Any]:

    private def deps: Vector[Task[Int]] =
        (1 to 8).map(i => Task.input(s"d$i")(i)).toVector

    "Task.cached accepts 8 dependencies" in {
        val ds = deps
        val goal = Task.cached("cached-8")(ds(0), ds(1), ds(2), ds(3), ds(4), ds(5), ds(6), ds(7)) {
            (a, b, c, d, e, f, g, h) => a + b + c + d + e + f + g + h
        }
        for
            driver <- WorkflowTestDriver.init
            result <- driver.run(goal)
        yield assert(result == 36)
    }

    "Task.init accepts 8 dependencies as the cached alias" in {
        val ds = deps
        val goal = Task.init("init-8")(ds(0), ds(1), ds(2), ds(3), ds(4), ds(5), ds(6), ds(7)) {
            (a, b, c, d, e, f, g, h) => a * b * c * d * e * f * g * h
        }
        val cached = goal.asInstanceOf[Task.Cached[Int]]
        assert(cached.deps.size == 8)
    }

    "Task.persistent accepts 8 dependencies" in {
        val ds = deps
        val goal = Task.persistent("persistent-8")(ds(0), ds(1), ds(2), ds(3), ds(4), ds(5), ds(6), ds(7)) {
            (a, b, c, d, e, f, g, h) => a + b + c + d + e + f + g + h
        }
        for
            driver <- WorkflowTestDriver.init
            result <- driver.run(goal)
        yield assert(result == 36)
    }

    "Task.activity accepts 8 dependencies" in {
        val ds = deps
        val goal = Task.activity("activity-8")(ds(0), ds(1), ds(2), ds(3), ds(4), ds(5), ds(6), ds(7)) {
            (a, b, c, d, e, f, g, h) => a + b + c + d + e + f + g + h
        }
        for
            driver <- WorkflowTestDriver.init
            result <- driver.run(goal)
        yield assert(result == 36)
    }

    "Task.command accepts 8 dependencies" in {
        val ds = deps
        val goal = Task.command("command-8")(ds(0), ds(1), ds(2), ds(3), ds(4), ds(5), ds(6), ds(7)) {
            (a, b, c, d, e, f, g, h) => a + b + c + d + e + f + g + h
        }
        for
            driver <- WorkflowTestDriver.init
            result <- driver.run(goal)
        yield assert(result == 36)
    }

    "parameterized Task constructors accept 8 dependencies" in {
        val ds = deps
        val cached = Task.cached[Int, Int, Int, Int, Int, Int, Int, Int, Int, Int]("param-cached-8")(
            ds(0),
            ds(1),
            ds(2),
            ds(3),
            ds(4),
            ds(5),
            ds(6),
            ds(7)
        )((p, a, b, c, d, e, f, g, h) => p + a + b + c + d + e + f + g + h)
        val persistent = Task.persistent[Int, Int, Int, Int, Int, Int, Int, Int, Int, Int]("param-persistent-8")(
            ds(0),
            ds(1),
            ds(2),
            ds(3),
            ds(4),
            ds(5),
            ds(6),
            ds(7)
        )((p, a, b, c, d, e, f, g, h) => p + a + b + c + d + e + f + g + h)
        val activity = Task.activity[Int, Int, Int, Int, Int, Int, Int, Int, Int, Int]("param-activity-8")(
            ds(0),
            ds(1),
            ds(2),
            ds(3),
            ds(4),
            ds(5),
            ds(6),
            ds(7)
        )((p, a, b, c, d, e, f, g, h) => p + a + b + c + d + e + f + g + h)
        val command = Task.command[Int, Int, Int, Int, Int, Int, Int, Int, Int, Int]("param-command-8")(
            ds(0),
            ds(1),
            ds(2),
            ds(3),
            ds(4),
            ds(5),
            ds(6),
            ds(7)
        )((p, a, b, c, d, e, f, g, h) => p + a + b + c + d + e + f + g + h)
        for
            driver <- WorkflowTestDriver.init
            results <- Workflow.handle(driver.runtime)(
                Workflow.runAll(cached(10), persistent(20), activity(30), command(40))
            )
        yield assert(results == Chunk(46, 56, 66, 76))
    }

end TaskArityTests
