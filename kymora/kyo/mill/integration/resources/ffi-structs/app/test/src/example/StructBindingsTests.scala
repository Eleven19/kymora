package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi
import kyo.test.*

class StructBindingsTests extends Test[Any]:
  "vendored C bindings cover nested structs, packed structs, multi-value returns, and string fields" in {
    import AllowUnsafe.embrace.danger

    val bindings = Ffi.load[StructBindings]
    assert(bindings.structsLineSum(Line(Point(1, 2), Point(3, 4))) == 10)
    assert(bindings.structsPackedCompute(Packed(1, 42)) == 420)
    assert(bindings.structsPackedCompute(Packed(0, 42)) == 42)
    assert(bindings.structsPair(3, 5) == Pair(8, 15))
    assert(bindings.structsStatus(0) == StatusInfo(0, "OK"))
    assert(bindings.structsStatus(1) == StatusInfo(1, "ERR"))
  }
