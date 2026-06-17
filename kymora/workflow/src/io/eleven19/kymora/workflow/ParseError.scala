package io.eleven19.kymora.workflow

import kyo.*

/** Structured parse failures for workflow value types.
  *
  * Extends `RuntimeException` so the same value can be threaded through
  * Abort/Result channels (errors-as-values) AND thrown from total
  * encoders/decoders (e.g. kyo-schema's `readFn: Reader => A`, which has no
  * Result channel of its own).
  *
  * Each variant supplies a short `description` (the headline summary)
  * and an optional `detailedDescription` carrying the expected form,
  * known-good values, or other repair hints. `getMessage` concatenates
  * the two so a bare throw surfaces both halves; pattern-match consumers
  * can read either field directly without parsing the message.
  *
  * `detailedDescription` is a constructor field on each variant — the
  * companion defines a sensible default so most call sites just say
  * `MalformedTaskVersion("x.y")`, and a use-site that has extra context
  * can pass its own `Maybe[String]` to override.
  */
sealed abstract class ParseError(
    val description: String,
    val detailedDescription: Maybe[String] = Maybe.empty,
) extends RuntimeException(
      detailedDescription.fold(description)(d => s"$description ($d)"),
    ) derives CanEqual

object ParseError:
  final case class MalformedTaskVersion(
      raw: String,
      override val detailedDescription: Maybe[String] = MalformedTaskVersion.DefaultDetail,
  ) extends ParseError(s"Malformed TaskVersion: $raw", detailedDescription)

  object MalformedTaskVersion:
    val DefaultDetail: Maybe[String] =
      Maybe("expected form: <major>.<minor>.<patch> with non-negative components")

  final case class MalformedVPathRef(
      raw: String,
      override val detailedDescription: Maybe[String] = MalformedVPathRef.DefaultDetail,
  ) extends ParseError(s"Malformed VPathRef: $raw", detailedDescription)

  object MalformedVPathRef:
    val DefaultDetail: Maybe[String] =
      Maybe("expected wire form: vref:<tag>:<algo>:<hex>:<path>")

  final case class UnknownVPathRefTag(
      raw: String,
      override val detailedDescription: Maybe[String] = UnknownVPathRefTag.DefaultDetail,
  ) extends ParseError(s"Unknown VPathRef tag: $raw", detailedDescription)

  object UnknownVPathRefTag:
    val DefaultDetail: Maybe[String] =
      Maybe("known tags: v0c (content-hash), v0q (size+mtime quick-hash)")

  given Schema[ParseError] = Schema.derived
end ParseError
