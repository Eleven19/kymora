package example

import kyo.AllowUnsafe
import kyo.ffi.*

trait BufferBindings extends Ffi:
  def buffersSum(values: Buffer[Int], len: Int)(using AllowUnsafe): Int
  def buffersIncrement(values: Buffer[Int], len: Int)(using AllowUnsafe): Unit

object BufferBindings extends Ffi.Config(library = "buffers")
