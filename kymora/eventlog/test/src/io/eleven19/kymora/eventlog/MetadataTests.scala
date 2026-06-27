package io.eleven19.kymora.eventlog

import kyo.*
import kyo.test.*
import scala.compiletime.testing.typeCheckErrors

class MetadataTests extends Test[Any]:

    "MetadataKey accepts dotted path keys" in {
        assert(MetadataKey("session.id").map(_.show).getOrThrow == "session.id")
        assert(MetadataKey("trace.correlation_id").map(_.segments).getOrThrow == Chunk("trace", "correlation_id"))
    }

    "MetadataKey rejects empty keys and empty path segments" in {
        assert(MetadataKey("").isFailure)
        assert(MetadataKey("foo..bar").isFailure)
        assert(MetadataKey(".foo").isFailure)
        assert(MetadataKey("foo.").isFailure)
    }

    "MetadataKey does not expose an unsafe constructor" in {
        val errors = typeCheckErrors("""io.eleven19.kymora.eventlog.MetadataKey.unsafe("foo..bar")""")
        assert(errors.nonEmpty)
    }

    "MetadataValue converts to and from kyo Structure.Value without loss" in {
        val value =
            MetadataValue.Record(
                Chunk(
                    "session"  -> MetadataValue.Record(Chunk("id" -> MetadataValue.Str("abc"))),
                    "attempts" -> MetadataValue.Integer(2L),
                    "flags"    -> MetadataValue.Sequence(Chunk(MetadataValue.Bool(true), MetadataValue.Null)),
                    "status"   -> MetadataValue.VariantCase("Succeeded", MetadataValue.Null),
                    "attributes" -> MetadataValue.MapEntries(
                        Chunk(
                            MetadataValue.Str("duration_ms") -> MetadataValue.Decimal(12.5d),
                            MetadataValue.Str("cost")        -> MetadataValue.BigNum(BigDecimal("1234567890.123456789"))
                        )
                    )
                )
            )

        assert(MetadataValue.fromStructure(value.toStructure) == value)
    }
end MetadataTests
