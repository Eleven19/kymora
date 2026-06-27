package io.eleven19.kymora.eventlog

import kyo.*

opaque type StreamId = String

object StreamId:

    def apply(value: String): Result[JournalError.InvalidIdentifier, StreamId] =
        if value.isEmpty then Result.fail(JournalError.InvalidIdentifier("StreamId", value))
        else Result.succeed(value)

    extension (streamId: StreamId) def value: String = streamId

    given CanEqual[StreamId, StreamId] = CanEqual.derived
end StreamId

opaque type EventId = String

object EventId:

    def apply(value: String): Result[JournalError.InvalidIdentifier, EventId] =
        if value.isEmpty then Result.fail(JournalError.InvalidIdentifier("EventId", value))
        else Result.succeed(value)

    extension (eventId: EventId) def value: String = eventId

    given CanEqual[EventId, EventId] = CanEqual.derived
end EventId

opaque type EventType = String

object EventType:

    def apply(value: String): Result[JournalError.InvalidIdentifier, EventType] =
        if value.isEmpty then Result.fail(JournalError.InvalidIdentifier("EventType", value))
        else Result.succeed(value)

    extension (eventType: EventType) def value: String = eventType

    given CanEqual[EventType, EventType] = CanEqual.derived
end EventType

opaque type StreamRevision = Long

object StreamRevision:

    val first: StreamRevision = 0L

    def apply(value: Long): Result[JournalError.InvalidIdentifier, StreamRevision] =
        if value < 0L || value == Long.MaxValue then
            Result.fail(JournalError.InvalidIdentifier("StreamRevision", value.toString))
        else Result.succeed(value)

    extension (revision: StreamRevision) def value: Long = revision

    given CanEqual[StreamRevision, StreamRevision] = CanEqual.derived
end StreamRevision

opaque type StreamVersion = Long

object StreamVersion:

    val initial: StreamVersion = 0L

    def apply(value: Long): Result[JournalError.InvalidIdentifier, StreamVersion] =
        if value < 0L then Result.fail(JournalError.InvalidIdentifier("StreamVersion", value.toString))
        else Result.succeed(value)

    def after(revision: StreamRevision): StreamVersion =
        revision.value + 1L

    extension (version: StreamVersion) def value: Long = version

    given CanEqual[StreamVersion, StreamVersion] = CanEqual.derived
end StreamVersion

enum ExpectedRevision derives CanEqual:
    case Any
    case NoStream
    case Exact(revision: StreamRevision)
end ExpectedRevision

final case class EventMetadata(values: Map[MetadataKey, MetadataValue]) derives CanEqual

object EventMetadata:
    val empty: EventMetadata = EventMetadata(Map.empty)
end EventMetadata

final case class EventEnvelope(
    id: EventId,
    eventType: EventType,
    payload: Span[Byte],
    metadata: EventMetadata
) derives CanEqual

final case class RecordedEvent(
    streamId: StreamId,
    revision: StreamRevision,
    eventId: EventId,
    eventType: EventType,
    payload: Span[Byte],
    metadata: EventMetadata
) derives CanEqual

enum StreamInfo derives CanEqual:
    case Absent
    case Existing(eventCount: Long, lastRevision: StreamRevision)

    def exists: Boolean =
        this match
            case StreamInfo.Absent         => false
            case StreamInfo.Existing(_, _) => true
end StreamInfo

final case class AppendResult(
    streamId: StreamId,
    firstRevision: StreamRevision,
    lastRevision: StreamRevision,
    streamInfo: StreamInfo
) derives CanEqual
