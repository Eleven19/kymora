package io.eleven19.kymora.workflow.internal

import io.eleven19.kymora.workflow.*
import kyo.*
import kyo.test.*

class GraphTests extends Test[Any]:
  "Graph.collect returns reachable tasks from a single goal" in {
    val a      = Task.init("a")(0)
    val b      = Task.init("b")(a) { x => x + 1 }
    val result = Graph.collect(Seq(b))
    assert(result.isRight)
    val g = result.toOption.get
    assert(g.size == 2)
    assert(g.contains(TaskId("a")))
    assert(g.contains(TaskId("b")))
  }
  "Graph.collect dedupes shared subgraphs" in {
    val a      = Task.init("a")(0)
    val b      = Task.init("b")(a) { x => x + 1 }
    val c      = Task.init("c")(a) { x => x + 2 }
    val d      = Task.init("d")(b, c) { (x, y) => x + y }
    val result = Graph.collect(Seq(d))
    assert(result.isRight)
    val g = result.toOption.get
    assert(g.size == 4) // a once, not twice
  }
  "Graph.collect raises DuplicateTaskId for two distinct nodes with same id" in {
    val a1     = Task.init("a")(0)
    val a2     = Task.init("a")(0)
    val result = Graph.collect(Seq(a1, a2))
    assert(result.isLeft)
    result.left.toOption.get match
      case WorkflowError.DuplicateTaskId(id, _) => assert(id == TaskId("a"))
      case other                                => assert(false, s"expected DuplicateTaskId, got: $other")
  }
end GraphTests
