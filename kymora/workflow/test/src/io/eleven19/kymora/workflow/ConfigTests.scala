package io.eleven19.kymora.workflow

import io.eleven19.kymora.vfs.*
import kyo.*
import kyo.test.*

class ConfigTests extends Test[Any]:
  "Config.default constructs with sensible defaults" in {
    val cfg = Workflow.Config.default
    assert(cfg.parallelism > 0)
    assert(!cfg.continueOnError)
    assert(!cfg.readOnly)
    assert(!cfg.noCache)
  }
  "Config exposes Env-readable fields" in {
    val cfg = Workflow.Config.default
    for
      p <- Env.run(cfg)(Env.use[Workflow.Config](_.parallelism))
    yield assert(p == cfg.parallelism)
  }

  "Runtime defaults codec when constructed with four arguments" in {
    for
      vfs <- Vfs.inMemory.init
      runtime = Workflow.Runtime(
                  Workflow.Config.default,
                  vfs,
                  VPath("cache"),
                  Observer.NoOp,
                )
    yield assert(runtime.codec.isInstanceOf[Json])
  }

  "Runtime accepts explicit codec as fifth argument" in {
    for
      vfs <- Vfs.inMemory.init
      runtime = Workflow.Runtime(
                  Workflow.Config.default,
                  vfs,
                  VPath("cache"),
                  Observer.NoOp,
                  Protobuf(),
                )
    yield assert(runtime.codec.isInstanceOf[Protobuf])
  }

  "Runtime can be constructed from only a VFS backend" in {
    for
      vfs <- Vfs.inMemory.init
      runtime = Workflow.Runtime(vfs)
    yield
      assert(runtime.config.parallelism > 0)
      assert(runtime.cacheRoot == VPath("cache"))
      assert(runtime.observer eq Observer.NoOp)
      assert(runtime.codec.isInstanceOf[Json])
  }

  "Runtime can be constructed with only a named VFS backend" in {
    for
      vfs <- Vfs.inMemory.init
      runtime = Workflow.Runtime(vfs = vfs)
    yield
      assert(runtime.config.parallelism > 0)
      assert(runtime.cacheRoot == VPath("cache"))
      assert(runtime.observer eq Observer.NoOp)
      assert(runtime.codec.isInstanceOf[Json])
  }

  "Runtime.default uses the same constructor defaults" in {
    for runtime <- Workflow.Runtime.default
    yield
      assert(runtime.config.parallelism > 0)
      assert(runtime.cacheRoot == VPath("cache"))
      assert(runtime.observer eq Observer.NoOp)
      assert(runtime.codec.isInstanceOf[Json])
  }
end ConfigTests
