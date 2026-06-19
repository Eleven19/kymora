package io.eleven19.kymora.workflow.store.internal

import kyo.*

/** Scala.js/Wasm variant of the per-key in-process mutex.
  *
  * The event loop is single-threaded, but Kyo fibers can still interleave at
  * async boundaries while sharing the same `.dest/`. A tiny cooperative mutex
  * is enough here and avoids unavailable `java.util.concurrent` primitives.
  */
private[workflow] final class PersistentMutex:
  private val held = scala.collection.mutable.Set.empty[String]

  def acquire(name: String)(using Frame): Unit < Async =
    def loop: Unit < Async =
      Sync.Unsafe.defer {
        if held.contains(name) then false
        else
          val _ = held.add(name)
          true
      }.flatMap { acquired =>
        if acquired then ()
        else Async.sleep(1.millis).andThen(loop)
      }
    loop

  def release(name: String)(using Frame): Unit < Sync =
    Sync.Unsafe.defer {
      val _ = held.remove(name)
      ()
    }
end PersistentMutex
