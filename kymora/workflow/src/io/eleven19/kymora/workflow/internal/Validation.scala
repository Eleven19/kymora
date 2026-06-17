package io.eleven19.kymora.workflow.internal

import kyo.*

private[workflow] object Validation:

  enum Reason derives CanEqual:
    case Empty
    case ContainsSlash
    case ContainsBackslash
    case ContainsDotDot
    case LeadingDot
    case TrailingDot
    case EmptySegment
    case ReservedSegment(seg: String)
    case InvalidCharacter(seg: String, ch: Char)

  val Reserved: Set[String] = Set("__workflow__", "index", "lock")

  private inline def isSegmentChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
      (c >= '0' && c <= '9') || c == '_' || c == '-'

  def check(s: String): Result[Reason, String] =
    if s.isEmpty then Result.fail(Reason.Empty)
    else if s.contains('/') then Result.fail(Reason.ContainsSlash)
    else if s.contains('\\') then Result.fail(Reason.ContainsBackslash)
    else if s.contains("..") then Result.fail(Reason.ContainsDotDot)
    else if s.startsWith(".") then Result.fail(Reason.LeadingDot)
    else if s.endsWith(".") then Result.fail(Reason.TrailingDot)
    else
      val segments = s.split('.')
      var problem: Reason | Null = null
      var i = 0
      while problem == null && i < segments.length do
        val seg = segments(i)
        if seg.isEmpty then problem = Reason.EmptySegment
        else if Reserved.contains(seg) then problem = Reason.ReservedSegment(seg)
        else
          var j = 0
          while problem == null && j < seg.length do
            val c = seg.charAt(j)
            if !isSegmentChar(c) then problem = Reason.InvalidCharacter(seg, c)
            j += 1
        i += 1
      if problem == null then Result.succeed(s) else Result.fail(problem.nn)
end Validation
