package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi
import kyo.test.*

class SbtLayoutBindingsTests extends Test[Any]:
  "sbt-compatible FFI traits discover Scala in src/main/scala and C in src/main/c" in {
    import AllowUnsafe.embrace.danger

    val bindings = Ffi.load[SbtLayoutBindings]
    assert(bindings.layoutSbtAdd(10, 11) == 21)
  }
