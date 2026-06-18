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
    yield assert(attempt.isFailure)
  }
  "continueOnError=true is observable via Config" in {
    for
      driver <- WorkflowTestDriver.init
      cfg     = driver.config.copy(continueOnError = true)
    yield assert(cfg.continueOnError)
  }
end ParallelismTests
