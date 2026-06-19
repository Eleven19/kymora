package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi

trait TextKitBindings extends Ffi:
  def textkitCountWords(text: String)(using AllowUnsafe): Int
  def textkitHasPrefix(text: String, prefix: String)(using AllowUnsafe): Boolean
  def textkitSharedPrefixLength(left: String, right: String)(using AllowUnsafe): Int
  def textkitScoreTitle(title: String, keyword: String)(using AllowUnsafe): Double

object TextKitBindings extends Ffi.Config(library = "textkit")
