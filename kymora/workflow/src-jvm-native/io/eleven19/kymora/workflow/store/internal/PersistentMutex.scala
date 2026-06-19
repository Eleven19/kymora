package io.eleven19.kymora.workflow.store.internal

import kyo.*

/** Per-key in-process mutex backed by `java.util.concurrent.Semaphore`.
  *
  * Permits, not `ReentrantLock`: under Kyo's fiber scheduler an acquire can land on one carrier thread and the
  * Scope-driven release on another; `ReentrantLock.unlock` rejects that with `IllegalMonitorStateException`, permits
  * don't.
  */
final private[workflow] class PersistentMutex:

    private val map =
        new java.util.concurrent.ConcurrentHashMap[String, java.util.concurrent.Semaphore]()

    def acquire(name: String)(using Frame): Unit < Sync =
        val sem = map.computeIfAbsent(name, _ => new java.util.concurrent.Semaphore(1, true))
        Sync.defer(sem.acquire())

    def release(name: String)(using Frame): Unit < Sync =
        Sync.defer:
            val sem = map.get(name)
            if sem ne null then sem.release()
end PersistentMutex
