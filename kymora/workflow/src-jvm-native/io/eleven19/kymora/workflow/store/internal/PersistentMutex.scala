package io.eleven19.kymora.workflow.store.internal

/** Per-key in-process mutex backed by `java.util.concurrent.Semaphore`.
  *
  * Permits, not `ReentrantLock`: under Kyo's fiber scheduler an acquire can
  * land on one carrier thread and the Scope-driven release on another;
  * `ReentrantLock.unlock` rejects that with `IllegalMonitorStateException`,
  * permits don't.
  *
  * JS has its own no-op variant under `src-js/` — single-threaded, no
  * mutual exclusion needed, and the Scala.js `Semaphore` stub lacks
  * `acquire()` anyway.
  */
private[store] final class PersistentMutex:
  private val map =
    new java.util.concurrent.ConcurrentHashMap[String, java.util.concurrent.Semaphore]()

  def acquire(name: String): Unit =
    val sem = map.computeIfAbsent(name, _ => new java.util.concurrent.Semaphore(1, true))
    sem.acquire()

  def release(name: String): Unit =
    val sem = map.get(name)
    if sem ne null then sem.release()
end PersistentMutex
