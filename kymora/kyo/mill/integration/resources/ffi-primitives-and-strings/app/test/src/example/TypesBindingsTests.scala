package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi
import kyo.test.*

class TypesBindingsTests extends Test[Any]:
  "vendored C bindings cover primitive numbers, booleans, and strings" in {
    import AllowUnsafe.embrace.danger

    val types = Ffi.load[TypesBindings]
    assert(types.typesAddInt(2, 5) == 7)
    assert(types.typesMulLong(6L, 7L) == 42L)
    assert(types.typesHypot(3.0, 4.0) == 5.0)
    assert(types.typesIsEven(12))
    assert(!types.typesIsEven(13))
    assert(types.typesNameLength("Kymora") == 6)
  }
