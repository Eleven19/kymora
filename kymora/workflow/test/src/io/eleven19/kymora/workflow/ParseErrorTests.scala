package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class ParseErrorTests extends Test[Any]:
  "MalformedTaskVersion CanEqual comparison" in {
    val a = ParseError.MalformedTaskVersion("x")
    val b = ParseError.MalformedTaskVersion("x")
    assert(a == b)
  }
  "MalformedVfsPathRef CanEqual comparison" in {
    assert(ParseError.MalformedVfsPathRef("y") == ParseError.MalformedVfsPathRef("y"))
  }
  "UnknownVfsPathRefTag CanEqual comparison" in {
    assert(ParseError.UnknownVfsPathRefTag("v0z") == ParseError.UnknownVfsPathRefTag("v0z"))
  }
end ParseErrorTests
