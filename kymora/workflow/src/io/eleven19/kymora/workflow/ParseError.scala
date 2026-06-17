package io.eleven19.kymora.workflow

import kyo.*

sealed trait ParseError derives CanEqual, Schema
object ParseError:
  final case class MalformedTaskVersion(raw: String) extends ParseError
  final case class MalformedVfsPathRef(raw: String) extends ParseError
  final case class UnknownVfsPathRefTag(raw: String) extends ParseError
end ParseError
