package io.eleven19.kymora.workflow

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
end ConfigTests
