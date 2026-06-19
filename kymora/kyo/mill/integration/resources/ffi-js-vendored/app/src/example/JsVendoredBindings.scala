package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi

trait JsVendoredBindings extends Ffi:
  def jsvendoredAdd(a: Int, b: Int)(using AllowUnsafe): Int

object JsVendoredBindings extends Ffi.Config(library = "jsvendored")
