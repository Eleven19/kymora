package io.eleven19.kymora.eventlog

import kyo.*
import kyo.kernel.*

sealed trait Journal extends ArrowEffect[Journal.Op, Id]

object Journal:

    sealed trait Op[A]

    object Op:

        final case class Append(
            streamId: StreamId,
            expected: ExpectedRevision,
            events: Chunk[EventEnvelope]
        ) extends Op[AppendResult]

        final case class Read(
            streamId: StreamId,
            from: StreamRevision,
            maxCount: Int
        ) extends Op[Chunk[RecordedEvent]]

        final case class GetStreamInfo(streamId: StreamId) extends Op[StreamInfo]
    end Op

    trait Backend:

        def append(
            streamId: StreamId,
            expected: ExpectedRevision,
            events: Chunk[EventEnvelope]
        ): AppendResult < (Sync & Abort[JournalError])

        def read(
            streamId: StreamId,
            from: StreamRevision,
            maxCount: Int
        ): Chunk[RecordedEvent] < (Sync & Abort[JournalError])

        def streamInfo(streamId: StreamId): StreamInfo < (Sync & Abort[JournalError])
    end Backend

    def append(
        streamId: StreamId,
        expected: ExpectedRevision,
        events: Chunk[EventEnvelope]
    )(using Frame): AppendResult < Journal =
        ArrowEffect.suspend[AppendResult](Tag[Journal], Op.Append(streamId, expected, events))

    def read(
        streamId: StreamId,
        from: StreamRevision,
        maxCount: Int
    )(using Frame): Chunk[RecordedEvent] < Journal =
        ArrowEffect.suspend[Chunk[RecordedEvent]](Tag[Journal], Op.Read(streamId, from, maxCount))

    def streamInfo(streamId: StreamId)(using Frame): StreamInfo < Journal =
        ArrowEffect.suspend[StreamInfo](Tag[Journal], Op.GetStreamInfo(streamId))

    def run[A, S](
        backend: Backend
    )(program: A < (Journal & S))(using Frame): A < (S & Sync & Abort[JournalError]) =
        ArrowEffect.handleLoop(Tag[Journal], program):
            [C] =>
                (op, cont) =>
                    op match
                        case Op.Append(streamId, expected, events) =>
                            backend.append(streamId, expected, events).map(result => Loop.continue(cont(result)))
                        case Op.Read(streamId, from, maxCount) =>
                            backend.read(streamId, from, maxCount).map(result => Loop.continue(cont(result)))
                        case Op.GetStreamInfo(streamId) =>
                            backend.streamInfo(streamId).map(result => Loop.continue(cont(result)))
end Journal
