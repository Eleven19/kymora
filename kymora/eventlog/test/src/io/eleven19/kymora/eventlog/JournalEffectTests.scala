package io.eleven19.kymora.eventlog

import kyo.*
import kyo.test.*

class JournalEffectTests extends Test[Any]:

    private def validIdentifier[A](result: Result[JournalError.InvalidIdentifier, A]): A =
        result.getOrElse(throw AssertionError("expected valid eventlog identifier"))

    private val streamId  = validIdentifier(StreamId("users-1"))
    private val eventId   = validIdentifier(EventId("event-1"))
    private val eventType = validIdentifier(EventType("UserRegistered"))

    private val envelope = EventEnvelope(
        id = eventId,
        eventType = eventType,
        payload = Span.from("""{"name":"Ada"}""".getBytes("UTF-8")),
        metadata = EventMetadata.empty
    )

    private val recorded = RecordedEvent(
        streamId = streamId,
        revision = StreamRevision.first,
        eventId = eventId,
        eventType = eventType,
        payload = envelope.payload,
        metadata = envelope.metadata
    )
    private val expectedInfo = StreamInfo.Existing(1L, StreamRevision.first)
    private val appendResult = AppendResult(streamId, StreamRevision.first, StreamRevision.first, expectedInfo)

    "public operations expose the Journal effect" in {
        val append: AppendResult < Journal =
            Journal.append(streamId, ExpectedRevision.NoStream, Chunk(envelope))
        val read: Chunk[RecordedEvent] < Journal =
            Journal.read(streamId, StreamRevision.first, 10)
        val info: StreamInfo < Journal =
            Journal.streamInfo(streamId)

        succeed("type ascriptions verify the public Journal effect API")
    }

    "run delegates append, read, and streamInfo to the backend" in {
        val backend = RecordingBackend()

        val program =
            for
                appended <- Journal.append(streamId, ExpectedRevision.NoStream, Chunk(envelope))
                events   <- Journal.read(streamId, StreamRevision.first, 10)
                info     <- Journal.streamInfo(streamId)
            yield (appended, events, info)

        Journal.run(backend)(program).map { case (appended, events, info) =>
            assert(appended == appendResult)
            assert(events == Chunk(recorded))
            assert(info == expectedInfo)
            assert(
                backend.calls == List(
                    BackendCall.Append(streamId, ExpectedRevision.NoStream, Chunk(envelope)),
                    BackendCall.Read(streamId, StreamRevision.first, 10),
                    BackendCall.StreamInfo(streamId)
                )
            )
        }
    }

    "run preserves backend aborts" in {
        val backend = FailingBackend(JournalError.EmptyAppend)

        Abort
            .run[JournalError] {
                Journal.run(backend) {
                    Journal.append(streamId, ExpectedRevision.NoStream, Chunk.empty)
                }
            }
            .map(result => assert(result == Result.fail(JournalError.EmptyAppend)))
    }

    private enum BackendCall derives CanEqual:
        case Append(streamId: StreamId, expected: ExpectedRevision, events: Chunk[EventEnvelope])
        case Read(streamId: StreamId, from: StreamRevision, maxCount: Int)
        case StreamInfo(streamId: StreamId)
    end BackendCall

    final private class RecordingBackend extends Journal.Backend:
        private var recordedCalls: List[BackendCall] = Nil

        def calls: List[BackendCall] =
            recordedCalls.reverse

        def append(
            streamId: StreamId,
            expected: ExpectedRevision,
            events: Chunk[EventEnvelope]
        ): AppendResult < (Sync & Abort[JournalError]) =
            Sync.defer {
                recordedCalls = BackendCall.Append(streamId, expected, events) :: recordedCalls
                appendResult
            }

        def read(
            streamId: StreamId,
            from: StreamRevision,
            maxCount: Int
        ): Chunk[RecordedEvent] < (Sync & Abort[JournalError]) =
            Sync.defer {
                recordedCalls = BackendCall.Read(streamId, from, maxCount) :: recordedCalls
                Chunk(recorded)
            }

        def streamInfo(streamId: StreamId): StreamInfo < (Sync & Abort[JournalError]) =
            Sync.defer {
                recordedCalls = BackendCall.StreamInfo(streamId) :: recordedCalls
                expectedInfo
            }
    end RecordingBackend

    private object RecordingBackend:

        def apply(): RecordingBackend =
            new RecordingBackend
    end RecordingBackend

    final private case class FailingBackend(error: JournalError) extends Journal.Backend:

        def append(
            streamId: StreamId,
            expected: ExpectedRevision,
            events: Chunk[EventEnvelope]
        ): AppendResult < (Sync & Abort[JournalError]) =
            Abort.fail(error)

        def read(
            streamId: StreamId,
            from: StreamRevision,
            maxCount: Int
        ): Chunk[RecordedEvent] < (Sync & Abort[JournalError]) =
            Abort.fail(error)

        def streamInfo(streamId: StreamId): StreamInfo < (Sync & Abort[JournalError]) =
            Abort.fail(error)
    end FailingBackend
end JournalEffectTests
