package example

import kyo.AllowUnsafe
import kyo.ffi.*

trait CallbackBindings extends Ffi:
  def callbacksSumPairs(callback: (Int, Int) => Int, count: Int)(using AllowUnsafe): Int
  def callbacksSort(values: Buffer[Int], len: Int, compare: (Int, Int) => Int)(using AllowUnsafe): Unit
  def callbacksRegister(callback: Int => Unit, guard: Ffi.Guard)(using AllowUnsafe): Unit
  def callbacksFire(value: Int)(using AllowUnsafe): Unit

object CallbackBindings extends Ffi.Config(library = "callbacks")
