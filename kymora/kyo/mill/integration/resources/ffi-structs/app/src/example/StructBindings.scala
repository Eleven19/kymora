package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi

case class Point(x: Int, y: Int)
case class Line(a: Point, b: Point)
case class Packed(tag: Int, value: Int)
case class Pair(sum: Int, product: Int)
case class StatusInfo(code: Int, message: String)

trait StructBindings extends Ffi:
  def structsLineSum(line: Line)(using AllowUnsafe): Int
  def structsPackedCompute(packed: Packed)(using AllowUnsafe): Int
  def structsPair(a: Int, b: Int)(using AllowUnsafe): Pair
  def structsStatus(code: Int)(using AllowUnsafe): StatusInfo

object StructBindings extends Ffi.Config(library = "structs", packedStructs = Set("Packed"))
