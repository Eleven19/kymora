package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi
import kyo.test.*

class JsVendoredBindingsTests extends Test[Any]:
  "Scala.js FFI loads a vendored C library through koffi" in {
    import AllowUnsafe.embrace.danger

    val bindings = Ffi.load[JsVendoredBindings]
    assert(bindings.jsvendoredAdd(20, 22) == 42)
  }
