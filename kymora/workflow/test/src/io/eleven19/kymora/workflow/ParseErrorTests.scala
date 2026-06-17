package io.eleven19.kymora.workflow

import kyo.*
import kyo.test.*

class ParseErrorTests extends Test[Any]:
  "MalformedTaskVersion CanEqual comparison" in {
    val a = ParseError.MalformedTaskVersion("x")
    val b = ParseError.MalformedTaskVersion("x")
    assert(a == b)
  }
  "MalformedVPathRef CanEqual comparison" in {
    assert(ParseError.MalformedVPathRef("y") == ParseError.MalformedVPathRef("y"))
  }
  "UnknownVPathRefTag CanEqual comparison" in {
    assert(ParseError.UnknownVPathRefTag("v0z") == ParseError.UnknownVPathRefTag("v0z"))
  }
end ParseErrorTests
