package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi

trait LayoutBindings extends Ffi:
  def layoutAdd(a: Int, b: Int)(using AllowUnsafe): Int

object LayoutBindings extends Ffi.Config(library = "layout")
