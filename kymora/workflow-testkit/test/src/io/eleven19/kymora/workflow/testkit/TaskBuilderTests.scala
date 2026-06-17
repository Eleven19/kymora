package io.eleven19.kymora.workflow.testkit

import io.eleven19.kymora.workflow.*
import kyo.*
import kyo.test.*

class TaskBuilderTests extends Test[Any]:
  "linearChain(3) creates a 3-task linear dependency" in {
    val chain = TaskBuilder.linearChain(3)
    assert(chain.size == 3)
    assert(chain(0).id == TaskId("chain.t0"))
    assert(chain(1).id == TaskId("chain.t1"))
    assert(chain(2).id == TaskId("chain.t2"))
  }
  "diamond(2) creates 1 head + 2 mids + 1 tail" in {
    val d = TaskBuilder.diamond(2)
    // d has named handles: head, mids, tail
    assert(d.head.id == TaskId("diamond.head"))
    assert(d.mids.size == 2)
    assert(d.mids(0).id == TaskId("diamond.mid0"))
    assert(d.mids(1).id == TaskId("diamond.mid1"))
    assert(d.tail.id == TaskId("diamond.tail"))
  }
  "sourceInputChain wires a Source, Input, and Task.Cached" in {
    val chain = TaskBuilder.sourceInputChain
    assert(chain.source.id == TaskId("siChain.source"))
    assert(chain.input.id == TaskId("siChain.input"))
    assert(chain.compile.id == TaskId("siChain.compile"))
  }
end TaskBuilderTests
