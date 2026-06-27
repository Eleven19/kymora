package io.eleven19.kymora.eventlog

import kyo.*

opaque type MetadataKey = String

object MetadataKey:

    enum Reason(message: String) extends RuntimeException(message) derives CanEqual:
        case EmptyKey     extends Reason("metadata key must not be empty")
        case EmptySegment extends Reason("metadata key segments must not be empty")

    def apply(value: String): Result[Reason, MetadataKey] =
        if value.isEmpty then Result.fail(Reason.EmptyKey)
        else if value.startsWith(".") || value.endsWith(".") || value.contains("..") then
            Result.fail(Reason.EmptySegment)
        else Result.succeed(value)

    extension (key: MetadataKey)
        def show: String = key

        def segments: Chunk[String] = Chunk.from(key.split("\\.").toSeq)

    given CanEqual[MetadataKey, MetadataKey] = CanEqual.derived
end MetadataKey

enum MetadataValue derives CanEqual:
    case Record(fields: Chunk[(String, MetadataValue)])
    case VariantCase(name: String, value: MetadataValue)
    case Sequence(elements: Chunk[MetadataValue])
    case MapEntries(entries: Chunk[(MetadataValue, MetadataValue)])
    case Str(value: String)
    case Bool(value: Boolean)
    case Integer(value: Long)
    case Decimal(value: Double)
    case BigNum(value: BigDecimal)
    case Null

    def toStructure: Structure.Value =
        this match
            case MetadataValue.Record(fields) =>
                Structure.Value.Record(fields.map((name, value) => name -> value.toStructure))
            case MetadataValue.VariantCase(name, value) =>
                Structure.Value.VariantCase(name, value.toStructure)
            case MetadataValue.Sequence(elements) =>
                Structure.Value.Sequence(elements.map(_.toStructure))
            case MetadataValue.MapEntries(entries) =>
                Structure.Value.MapEntries(entries.map((key, value) => key.toStructure -> value.toStructure))
            case MetadataValue.Str(value) =>
                Structure.Value.Str(value)
            case MetadataValue.Bool(value) =>
                Structure.Value.Bool(value)
            case MetadataValue.Integer(value) =>
                Structure.Value.Integer(value)
            case MetadataValue.Decimal(value) =>
                Structure.Value.Decimal(value)
            case MetadataValue.BigNum(value) =>
                Structure.Value.BigNum(value)
            case MetadataValue.Null =>
                Structure.Value.Null
end MetadataValue

object MetadataValue:

    def fromStructure(value: Structure.Value): MetadataValue =
        value match
            case Structure.Value.Record(fields) =>
                MetadataValue.Record(fields.map((name, value) => name -> fromStructure(value)))
            case Structure.Value.VariantCase(name, value) =>
                MetadataValue.VariantCase(name, fromStructure(value))
            case Structure.Value.Sequence(elements) =>
                MetadataValue.Sequence(elements.map(fromStructure))
            case Structure.Value.MapEntries(entries) =>
                MetadataValue.MapEntries(entries.map((key, value) => fromStructure(key) -> fromStructure(value)))
            case Structure.Value.Str(value) =>
                MetadataValue.Str(value)
            case Structure.Value.Bool(value) =>
                MetadataValue.Bool(value)
            case Structure.Value.Integer(value) =>
                MetadataValue.Integer(value)
            case Structure.Value.Decimal(value) =>
                MetadataValue.Decimal(value)
            case Structure.Value.BigNum(value) =>
                MetadataValue.BigNum(value)
            case Structure.Value.Null =>
                MetadataValue.Null
end MetadataValue
