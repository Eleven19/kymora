package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi

trait NativeVendoredBindings extends Ffi:
  def nativevendoredAdd(a: Int, b: Int)(using AllowUnsafe): Int

object NativeVendoredBindings extends Ffi.Config(library = "nativevendored", nativeBundled = true)
