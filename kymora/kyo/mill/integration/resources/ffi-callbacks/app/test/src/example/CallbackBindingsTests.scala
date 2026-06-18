package example

import kyo.AllowUnsafe
import kyo.ffi.*
import kyo.test.*

class CallbackBindingsTests extends Test[Any]:
  "vendored C bindings cover transient callbacks, buffer callbacks, and retained callbacks" in {
    import AllowUnsafe.embrace.danger

    val bindings = Ffi.load[CallbackBindings]
    assert(bindings.callbacksSumPairs((a, b) => a + b, 5) == 30)

    Buffer.use[Int, Unit](5) { buffer =>
      List(1, 4, 2, 5, 3).zipWithIndex.foreach { case (value, index) =>
        buffer.set(index, value)
      }
      bindings.callbacksSort(buffer, 5, (a, b) => if a > b then -1 else if a < b then 1 else 0)
      assert((0 until 5).map(buffer.get).toList == List(5, 4, 3, 2, 1))
    }

    var observed = -1
    Ffi.Guard.use { guard =>
      bindings.callbacksRegister((value: Int) => observed = value, guard)
      bindings.callbacksFire(42)
    }
    assert(observed == 42)
  }
