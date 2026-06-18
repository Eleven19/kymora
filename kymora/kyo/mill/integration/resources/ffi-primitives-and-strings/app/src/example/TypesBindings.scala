package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi

trait TypesBindings extends Ffi:
  def typesAddInt(a: Int, b: Int)(using AllowUnsafe): Int
  def typesMulLong(a: Long, b: Long)(using AllowUnsafe): Long
  def typesHypot(a: Double, b: Double)(using AllowUnsafe): Double
  def typesIsEven(value: Int)(using AllowUnsafe): Boolean
  def typesNameLength(name: String)(using AllowUnsafe): Int

object TypesBindings extends Ffi.Config(library = "types")
