package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi

trait SbtLayoutBindings extends Ffi:
  def layoutSbtAdd(a: Int, b: Int)(using AllowUnsafe): Int

object SbtLayoutBindings extends Ffi.Config(library = "layout_sbt")
