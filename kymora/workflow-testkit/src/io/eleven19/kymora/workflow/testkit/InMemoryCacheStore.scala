package io.eleven19.kymora.workflow.testkit

import io.eleven19.kymora.workflow.*
import io.eleven19.kymora.workflow.store.*
import io.eleven19.kymora.vfs.*
import kyo.*

/** A `CacheStore` backed by an in-memory [[Vfs]] for use in downstream
  * users' tests. Delegates entirely to [[VfsDirStore]] so behaviour matches
  * the production store exactly — only the underlying VFS changes.
  */
object InMemoryCacheStore:
  /** Constructs a `CacheStore` whose state lives entirely in a fresh
    * in-memory VFS rooted at `cache/`. */
  def init(using Frame): CacheStore < (Async & Abort[StoreError]) =
    for
      vfs   <- Vfs.inMemory.init
      root   = VPath("cache")
      store <- VfsDirStore.init(root, vfs)
    yield store
end InMemoryCacheStore
