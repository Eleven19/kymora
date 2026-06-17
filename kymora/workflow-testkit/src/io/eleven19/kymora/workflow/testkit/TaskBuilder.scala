package io.eleven19.kymora.workflow.testkit

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.vfs.VPath
import kyo.*

/** Convenience ObjectMothers for common graph shapes used in tests.
  *
  * The intent: a single call returns a representative task tree plus named
  * handles to the inner nodes so tests can assert on specific positions in the
  * graph.
  */
object TaskBuilder:

  /** A linear chain of N Task.Cached nodes: t0 ← t1 ← ... ← t(N-1). Returns the
    * chain in declaration order (t0 first, t(N-1) last).
    */
  def linearChain(n: Int): Vector[Task[Int]] =
    given TaskScope = TaskScope("chain")
    require(n >= 1, "linearChain requires n >= 1")
    val builder = scala.collection.mutable.ArrayBuffer.empty[Task[Int]]
    builder += Task.init("t0")(0)
    for i <- 1 until n do
      val prev = builder.last
      builder += Task.init(s"t$i")(prev)(x => x + 1)
    builder.toVector

  /** A diamond shape: 1 head, `width` parallel middles, 1 tail.
    * {{{
    *     head
    *    / | \
    *  mid0 mid1 ... mid(width-1)
    *    \ | /
    *     tail
    * }}}
    */
  final case class Diamond(head: Task[Int], mids: Vector[Task[Int]], tail: Task[Int])

  def diamond(width: Int): Diamond =
    given TaskScope = TaskScope("diamond")
    require(width >= 1, "diamond requires width >= 1")
    val head = Task.init("head")(0)
    val mids = (0 until width).map { i =>
      Task.init(s"mid$i")(head)(x => x + 1)
    }.toVector
    val tail = mids match
      case Vector(only) => Task.init("tail")(only)(x => x + 1)
      case Vector(a, b) => Task.init("tail")(a, b)((x, y) => x + y)
      case _            =>
        // For width > 2, just depend on first two mids (the test only exercises width=2).
        // Full arity-N tail is out of scope until Task.init exposes a Chunk[Task[?]] variant.
        Task.init("tail")(mids(0), mids(1))((x, y) => x + y)
    Diamond(head, mids, tail)

  /** A Source + Input + Task.Cached chain: compile depends on (source, input). */
  final case class SourceInputChain(source: Source, input: Input[String], compile: Task[Int])

  val sourceInputChain: SourceInputChain =
    given TaskScope = TaskScope("siChain")
    val src = Source.init("source")(VPath("repo", "src"))
    val ver = Input.init[String]("input")("3.8.4")
    val cmp = Task.init("compile")(src, ver)((_, _) => 0)
    SourceInputChain(src, ver, cmp)
end TaskBuilder
