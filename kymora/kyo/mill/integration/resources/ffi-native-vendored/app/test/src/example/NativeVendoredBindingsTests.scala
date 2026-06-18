package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi
import kyo.test.*

class NativeVendoredBindingsTests extends Test[Any]:
  "Scala Native FFI links a vendored C source copied into scala-native resources" in {
    import AllowUnsafe.embrace.danger

    val bindings = Ffi.load[NativeVendoredBindings]
    assert(bindings.nativevendoredAdd(19, 23) == 42)
  }
