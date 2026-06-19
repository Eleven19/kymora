package example

import kyo.AllowUnsafe
import kyo.ffi.*
import kyo.test.*

class BufferBindingsTests extends Test[Any]:
  "vendored C bindings read and mutate a Kyo FFI Buffer" in {
    import AllowUnsafe.embrace.danger

    val bindings = Ffi.load[BufferBindings]
    Buffer.use[Int, Unit](4) { buffer =>
      buffer.set(0, 1)
      buffer.set(1, 2)
      buffer.set(2, 3)
      buffer.set(3, 4)
      assert(bindings.buffersSum(buffer, 4) == 10)
      bindings.buffersIncrement(buffer, 4)
      assert((0 until 4).map(buffer.get).toList == List(2, 3, 4, 5))
    }
  }
