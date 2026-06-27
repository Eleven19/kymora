package io.eleven19.kymora.eventlog

import kyo.*
import kyo.test.*
import scala.compiletime.testing.typeCheckErrors

class EventModelTests extends Test[Any]:

    private def validIdentifier[A](result: Result[JournalError.InvalidIdentifier, A]): A =
        result.getOrElse(throw AssertionError("expected valid event model identifier"))

    private def validRevision(result: Result[JournalError.InvalidIdentifier, StreamRevision]): StreamRevision =
        result.getOrElse(throw AssertionError("expected valid stream revision"))

    "StreamRevision is zero-based and StreamVersion is one-based" in {
        assert(StreamRevision.first.value == 0L)
        assert(StreamVersion.initial.value == 0L)
        assert(StreamVersion.after(StreamRevision.first).value == 1L)
    }

    "StreamRevision rejects values that cannot produce a valid next version" in {
        assert(StreamRevision(-1L).isFailure)
        assert(StreamRevision(Long.MaxValue).isFailure)
        assert(StreamVersion.after(validRevision(StreamRevision(Long.MaxValue - 1L))).value == Long.MaxValue)
    }

    "ExpectedRevision supports any, no stream, and exact revision" in {
        assert(ExpectedRevision.Any != ExpectedRevision.NoStream)
        assert(ExpectedRevision.Exact(StreamRevision.first).revision == StreamRevision.first)
    }

    "EventEnvelope stores byte payload and metadata" in {
        val key = MetadataKey("session.id").getOrThrow
        val event = EventEnvelope(
            id = validIdentifier(EventId("evt-1")),
            eventType = validIdentifier(EventType("UserCreated")),
            payload = Span.from("{}".getBytes("UTF-8")),
            metadata = EventMetadata(Map(key -> MetadataValue.Str("s-1")))
        )
        assert(event.payload.size == 2)
        assert(event.metadata.values(key) == MetadataValue.Str("s-1"))
    }

    "public event model case classes support strict equality" in {
        val errors = typeCheckErrors(
            """
            import io.eleven19.kymora.eventlog.*
            import kyo.*

            val key = MetadataKey("session.id").getOrElse(throw AssertionError("key"))
            val streamId = StreamId("stream-1").getOrElse(throw AssertionError("stream"))
            val eventId = EventId("evt-1").getOrElse(throw AssertionError("event"))
            val eventType = EventType("UserCreated").getOrElse(throw AssertionError("type"))
            val revision = StreamRevision.first
            val payload = Span.from("{}".getBytes("UTF-8"))
            val metadata = EventMetadata(Map(key -> MetadataValue.Str("s-1")))
            val envelope = EventEnvelope(eventId, eventType, payload, metadata)
            val recorded = RecordedEvent(streamId, revision, eventId, eventType, payload, metadata)
            val result = AppendResult(streamId, revision, revision, StreamInfo.Existing(1L, revision))

            val metadataEqual: Boolean = metadata == EventMetadata.empty
            val envelopeEqual: Boolean = envelope == envelope
            val recordedEqual: Boolean = recorded == recorded
            val resultEqual: Boolean = result == result
            """
        )
        assert(errors.isEmpty)
    }

    "JournalError is not a Throwable API" in {
        val errors = typeCheckErrors(
            """val error: Throwable = io.eleven19.kymora.eventlog.JournalError.EmptyAppend"""
        )
        assert(errors.nonEmpty)
    }
end EventModelTests
