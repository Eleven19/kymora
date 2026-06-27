package io.eleven19.kymora.eventlog

import kyo.*
import kyo.test.*
import scala.compiletime.testing.typeCheckErrors

class InMemoryJournalTests extends Test[Any]:

    private val streamId = validIdentifier(StreamId("users-1"))

    private def validIdentifier[A](result: Result[JournalError.InvalidIdentifier, A]): A =
        result.getOrElse(throw AssertionError("expected valid eventlog identifier"))

    private def validRevision(result: Result[JournalError.InvalidIdentifier, StreamRevision]): StreamRevision =
        result.getOrElse(throw AssertionError("expected valid stream revision"))

    private def validMetadataKey(result: Result[MetadataKey.Reason, MetadataKey]): MetadataKey =
        result.getOrElse(throw AssertionError("expected valid metadata key"))

    private def event(number: Int, payload: String): EventEnvelope =
        EventEnvelope(
            id = validIdentifier(EventId(s"event-$number")),
            eventType = validIdentifier(EventType("UserChanged")),
            payload = Span.from(payload.getBytes("UTF-8")),
            metadata = EventMetadata(
                Map(validMetadataKey(MetadataKey("event.number")) -> MetadataValue.Integer(number.toLong))
            )
        )

    "EventLog entrypoint exposes runtime constructors, not scaffold metadata" in {
        val errors = typeCheckErrors(
            """
            import io.eleven19.kymora.eventlog.*
            val name = EventLog.name
            """
        )
        val backend: Journal.Backend < Sync =
            EventLog.inMemory.init
        assert(errors.nonEmpty)
    }

    "EventLog.inMemory.init constructs a backend with absent and existing stream info" in {
        val envelope = event(1, """{"name":"Ada"}""")
        val program =
            for
                backend <- EventLog.inMemory.init
                before  <- backend.streamInfo(streamId)
                result  <- backend.append(streamId, ExpectedRevision.NoStream, Chunk(envelope))
                after   <- backend.streamInfo(streamId)
            yield
                assert(before == StreamInfo.Absent)
                assert(result.streamInfo == StreamInfo.Existing(1L, StreamRevision.first))
                assert(after == StreamInfo.Existing(1L, StreamRevision.first))

        program
    }

    "append assigns consecutive zero-based revisions for multi-event appends" in {
        val first  = event(1, """{"name":"Ada"}""")
        val second = event(2, """{"name":"Grace"}""")
        val program =
            for
                backend  <- EventLog.inMemory.init
                result   <- backend.append(streamId, ExpectedRevision.NoStream, Chunk(first, second))
                recorded <- backend.read(streamId, StreamRevision.first, 10)
            yield
                assert(result.firstRevision == StreamRevision.first)
                assert(result.lastRevision == validRevision(StreamRevision(1L)))
                assert(result.streamInfo == StreamInfo.Existing(2L, validRevision(StreamRevision(1L))))
                assert(recorded.map(_.revision) == Chunk(StreamRevision.first, validRevision(StreamRevision(1L))))
                assert(recorded(0).eventId == first.id)
                assert(recorded(0).eventType == first.eventType)
                assert(recorded(0).payload.toArray.toSeq == first.payload.toArray.toSeq)
                assert(recorded(0).metadata == first.metadata)
                assert(recorded(1).eventId == second.id)
                assert(recorded(1).eventType == second.eventType)
                assert(recorded(1).payload.toArray.toSeq == second.payload.toArray.toSeq)
                assert(recorded(1).metadata == second.metadata)

        program
    }

    "empty append fails with EmptyAppend and leaves the stream absent" in {
        val program =
            for
                backend <- EventLog.inMemory.init
                result  <- Abort.run(backend.append(streamId, ExpectedRevision.NoStream, Chunk.empty))
                info    <- backend.streamInfo(streamId)
            yield
                assert(result == Result.fail(JournalError.EmptyAppend))
                assert(info == StreamInfo.Absent)

        program
    }

    "ExpectedRevision.NoStream conflicts after the stream exists" in {
        val envelope = event(1, """{"name":"Ada"}""")
        val program =
            for
                backend <- EventLog.inMemory.init
                _       <- backend.append(streamId, ExpectedRevision.NoStream, Chunk(envelope))
                result  <- Abort.run(backend.append(streamId, ExpectedRevision.NoStream, Chunk(event(2, "{}"))))
            yield assert(
                result == Result.fail(
                    JournalError.Conflict(
                        streamId,
                        ExpectedRevision.NoStream,
                        StreamInfo.Existing(1L, StreamRevision.first)
                    )
                )
            )

        program
    }

    "ExpectedRevision.Any appends even when the stream exists" in {
        val program =
            for
                backend <- EventLog.inMemory.init
                _       <- backend.append(streamId, ExpectedRevision.NoStream, Chunk(event(1, "{}")))
                result  <- backend.append(streamId, ExpectedRevision.Any, Chunk(event(2, "{}")))
                records <- backend.read(streamId, StreamRevision.first, 10)
            yield
                assert(result.firstRevision == validRevision(StreamRevision(1L)))
                assert(result.lastRevision == validRevision(StreamRevision(1L)))
                assert(result.streamInfo == StreamInfo.Existing(2L, validRevision(StreamRevision(1L))))
                assert(records.map(_.revision) == Chunk(StreamRevision.first, validRevision(StreamRevision(1L))))

        program
    }

    "stale ExpectedRevision.Exact conflicts with actual stream info" in {
        val program =
            for
                backend <- EventLog.inMemory.init
                _       <- backend.append(streamId, ExpectedRevision.NoStream, Chunk(event(1, "{}"), event(2, "{}")))
                result <- Abort.run(
                    backend.append(streamId, ExpectedRevision.Exact(StreamRevision.first), Chunk(event(3, "{}")))
                )
            yield assert(
                result == Result.fail(
                    JournalError.Conflict(
                        streamId,
                        ExpectedRevision.Exact(StreamRevision.first),
                        StreamInfo.Existing(2L, validRevision(StreamRevision(1L)))
                    )
                )
            )

        program
    }

    "correct ExpectedRevision.Exact appends the next event" in {
        val program =
            for
                backend <- EventLog.inMemory.init
                _       <- backend.append(streamId, ExpectedRevision.NoStream, Chunk(event(1, "{}")))
                result  <- backend.append(streamId, ExpectedRevision.Exact(StreamRevision.first), Chunk(event(2, "{}")))
                records <- backend.read(streamId, StreamRevision.first, 10)
            yield
                assert(result.firstRevision == validRevision(StreamRevision(1L)))
                assert(result.lastRevision == validRevision(StreamRevision(1L)))
                assert(result.streamInfo == StreamInfo.Existing(2L, validRevision(StreamRevision(1L))))
                assert(records.map(_.revision) == Chunk(StreamRevision.first, validRevision(StreamRevision(1L))))

        program
    }

    "bounded read returns records from the requested revision up to maxCount" in {
        val program =
            for
                backend <- EventLog.inMemory.init
                _ <- backend.append(
                    streamId,
                    ExpectedRevision.NoStream,
                    Chunk(event(1, "a"), event(2, "b"), event(3, "c"))
                )
                records <- backend.read(streamId, validRevision(StreamRevision(1L)), 1)
            yield
                assert(records.size == 1)
                assert(records(0).revision == validRevision(StreamRevision(1L)))
                assert(records(0).payload.toArray.toSeq == "b".getBytes("UTF-8").toSeq)

        program
    }

    "read returns empty for missing streams and non-positive maxCount" in {
        val program =
            for
                backend  <- EventLog.inMemory.init
                missing  <- backend.read(streamId, StreamRevision.first, 10)
                _        <- backend.append(streamId, ExpectedRevision.NoStream, Chunk(event(1, "a")))
                zero     <- backend.read(streamId, StreamRevision.first, 0)
                negative <- backend.read(streamId, StreamRevision.first, -1)
            yield
                assert(missing == Chunk.empty)
                assert(zero == Chunk.empty)
                assert(negative == Chunk.empty)

        program
    }
end InMemoryJournalTests
