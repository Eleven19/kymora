package io.eleven19.kymora.workflow.store.internal

/** Scala.js variant: no-op mutex.
  *
  * JS runs one fiber at a time on a single event-loop thread, so two
  * concurrent `openPersistentWorkspace` calls for the same key already
  * serialize naturally through fiber suspension. No host primitive is
  * required (and the Scala.js `java.util.concurrent.Semaphore` stub
  * omits blocking acquire methods anyway).
  */
private[store] final class PersistentMutex:
  def acquire(name: String): Unit = ()
  def release(name: String): Unit = ()
end PersistentMutex
