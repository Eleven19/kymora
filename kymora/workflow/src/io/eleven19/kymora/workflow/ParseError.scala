package io.eleven19.kymora.workflow

sealed trait ParseError derives CanEqual
object ParseError:
  final case class MalformedTaskVersion(raw: String) extends ParseError
end ParseError
