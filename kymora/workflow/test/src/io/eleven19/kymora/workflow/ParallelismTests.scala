package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.testkit.*
import kyo.*
import kyo.test.*

class ParallelismTests extends Test[Any]:
  "Independent leaves both execute (parallelism=2)" in {
    val a = Task.init("a")(1)
    val b = Task.init("b")(2)
    val c = Task.init("c")(a, b) { (x, y) => x + y }
    for
      driver <- WorkflowTestDriver.init
      runtime = driver.runtime.copy(config = driver.config.copy(parallelism = 2))
      result <- Workflow.handle(runtime)(Workflow.run(c))
    yield assert(result == 3)
  }
  "Independent leaves both execute under parallelism=1 (sequential fallback)" in {
    // Sanity check: parallelism=1 still produces correct results — the scheduler
    // simply falls through to Kyo.foreach. Documented limitation: real fan-out
    // parallelism (independent leaves running concurrently with measurable
    // overlap) is a follow-up to Task 47.
    val a = Task.init("a")(10)
    val b = Task.init("b")(20)
    val c = Task.init("c")(a, b) { (x, y) => x + y }
    for
      driver <- WorkflowTestDriver.init
      runtime = driver.runtime.copy(config = driver.config.copy(parallelism = 1))
      result <- Workflow.handle(runtime)(Workflow.run(c))
    yield assert(result == 30)
  }
  "Failed body aborts run by default (continueOnError=false)" in {
    val goal = Task.init("bad") { throw new RuntimeException("boom"); 0 }
    for
      driver  <- WorkflowTestDriver.init
      attempt <- Abort.run[WorkflowError](driver.run(goal))
    yield
      assert(attempt.isFailure)
      assert(attempt.fold(_ => false, _.isInstanceOf[WorkflowError.TaskFailed], _ => false))
  }
  "continueOnError=true accumulates independent task failures" in {
    for
      attempts <- AtomicRef.init(Chunk.empty[String])
      failA = Task.init("faila") {
        for
          _ <- attempts.updateAndGet(_.appended("faila")).unit
          _ <- Abort.fail[Throwable](new RuntimeException("boom-a"))
        yield 1
      }
      failB = Task.init("failb") {
        for
          _ <- attempts.updateAndGet(_.appended("failb")).unit
          _ <- Abort.fail[Throwable](new RuntimeException("boom-b"))
        yield 2
      }
      ok = Task.init("ok") {
        attempts.updateAndGet(_.appended("ok")).map(_ => 3)
      }
      driver <- WorkflowTestDriver.init
      runtime = driver.runtime.copy(config = driver.config.copy(continueOnError = true, parallelism = 2))
      attempt <- Abort.run[WorkflowError](Workflow.handle(runtime)(Workflow.runAll(failA, failB, ok)))
      ran     <- attempts.get
      events  <- driver.events
    yield
      val failedIds = events.collect { case WorkflowEvent.TaskFailed(id, _) => id }.toSet
      assert(ran.toSet == Set("faila", "failb", "ok"))
      assert(failedIds == Set(TaskId("faila"), TaskId("failb")))
      attempt match
        case Result.Failure(WorkflowError.Partial(errors)) =>
          val taskFailures = errors.collect { case e: WorkflowError.TaskFailed => e.id }.toSet
          assert(taskFailures == Set(TaskId("faila"), TaskId("failb")))
        case _ =>
          assert(false)
  }
  "continueOnError=true cancels dependents of failed tasks and keeps independent work running" in {
    for
      attempts <- AtomicRef.init(Chunk.empty[String])
      root = Task.init("root") {
        for
          _ <- attempts.updateAndGet(_.appended("root")).unit
          _ <- Abort.fail[Throwable](new RuntimeException("boom-root"))
        yield 1
      }
      blocked = Task.init("blocked")(root) { value =>
        attempts.updateAndGet(_.appended("blocked")).map(_ => value + 1)
      }
      sibling = Task.init("sibling") {
        attempts.updateAndGet(_.appended("sibling")).map(_ => 10)
      }
      driver <- WorkflowTestDriver.init
      runtime = driver.runtime.copy(config = driver.config.copy(continueOnError = true, parallelism = 2))
      attempt <- Abort.run[WorkflowError](Workflow.handle(runtime)(Workflow.runAll(blocked, sibling)))
      ran     <- attempts.get
      events  <- driver.events
    yield
      assert(ran.toSet == Set("root", "sibling"))
      assert(events.collect { case WorkflowEvent.TaskFailed(id, _) => id } == Chunk(TaskId("root")))
      assert(events.collect { case WorkflowEvent.TaskCancelled(id, _) => id } == Chunk(TaskId("blocked")))
      attempt match
        case Result.Failure(WorkflowError.Partial(errors)) =>
          assert(errors.collect { case e: WorkflowError.TaskFailed => e.id } == Chunk(TaskId("root")))
          assert(errors.collect { case e: WorkflowError.TaskCancelled => e.id } == Chunk(TaskId("blocked")))
        case _ =>
          assert(false)
  }
end ParallelismTests
