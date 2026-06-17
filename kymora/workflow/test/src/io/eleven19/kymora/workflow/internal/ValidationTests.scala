package io.eleven19.kymora.workflow.internal

import kyo.*
import kyo.test.*

class ValidationTests extends Test[Any]:
  "Validation.check rejects empty string" in {
    assert(Validation.check("") == Result.fail(Validation.Reason.Empty))
  }
  "Validation.check rejects a slash" in {
    assert(Validation.check("foo/bar") == Result.fail(Validation.Reason.ContainsSlash))
  }
  "Validation.check rejects a backslash" in {
    assert(Validation.check("foo\\bar") == Result.fail(Validation.Reason.ContainsBackslash))
  }
  "Validation.check rejects double-dot" in {
    assert(Validation.check("foo..bar") == Result.fail(Validation.Reason.ContainsDotDot))
  }
  "Validation.check rejects leading dot" in {
    assert(Validation.check(".foo") == Result.fail(Validation.Reason.LeadingDot))
  }
  "Validation.check rejects trailing dot" in {
    assert(Validation.check("foo.") == Result.fail(Validation.Reason.TrailingDot))
  }
  "Validation.check rejects empty segment" in {
    assert(Validation.check("foo..bar") != Result.succeed("foo..bar"))
  }
  "Validation.check rejects reserved segment" in {
    assert(Validation.check("kymora.__workflow__") == Result.fail(Validation.Reason.ReservedSegment("__workflow__")))
  }
  "Validation.check rejects invalid chars" in {
    val r = Validation.check("foo.bar!")
    assert(r == Result.fail(Validation.Reason.InvalidCharacter("bar!", '!')))
  }
  "Validation.check accepts simple dotted id" in {
    assert(Validation.check("kymora.vfs.jvm.compile") == Result.succeed("kymora.vfs.jvm.compile"))
  }
  "Validation.check accepts underscore and hyphen" in {
    assert(Validation.check("foo_bar.baz-qux") == Result.succeed("foo_bar.baz-qux"))
  }
end ValidationTests
