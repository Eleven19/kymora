package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi
import kyo.test.*

class LayoutBindingsTests extends Test[Any]:
  "Mill-native FFI defaults discover Scala in app/src and C in app/src/c" in {
    import AllowUnsafe.embrace.danger

    val bindings = Ffi.load[LayoutBindings]
    assert(bindings.layoutAdd(7, 8) == 15)
  }
