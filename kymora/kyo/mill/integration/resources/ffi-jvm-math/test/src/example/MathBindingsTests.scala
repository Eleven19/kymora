package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi
import kyo.test.*

class MathBindingsTests extends Test[Any]:
  "generated JVM binding calls a bundled C add function through Ffi.load" in {
    import AllowUnsafe.embrace.danger

    val math = Ffi.load[MathBindings]
    assert(math.mathAdd(2, 3) == 5)
  }
