package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi

trait MathBindings extends Ffi:
  def mathAdd(a: Int, b: Int)(using AllowUnsafe): Int

object MathBindings extends Ffi.Config(library = "math")
