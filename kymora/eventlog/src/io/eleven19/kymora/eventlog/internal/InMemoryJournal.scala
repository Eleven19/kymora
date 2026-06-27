package io.eleven19.kymora.eventlog.internal

import io.eleven19.kymora.eventlog.*
import kyo.*

private[eventlog] object InMemoryJournal:

    def init(using Frame): Journal.Backend < Sync =
        AtomicRef.init(State.empty).map(ref => InMemoryJournal(ref))

    final private case class State(streams: Map[StreamId, Chunk[RecordedEvent]])

    private object State:
        val empty: State = State(Map.empty)
    end State
end InMemoryJournal

final private class InMemoryJournal private (
    state: AtomicRef[InMemoryJournal.State]
)(using Frame)
    extends Journal.Backend:
    import InMemoryJournal.*

    def append(
        streamId: StreamId,
        expected: ExpectedRevision,
        events: Chunk[EventEnvelope]
    ): AppendResult < (Sync & Abort[JournalError]) =
        if events.isEmpty then Abort.fail(JournalError.EmptyAppend)
        else modify(current => appendToState(current, streamId, expected, events))

    def read(
        streamId: StreamId,
        from: StreamRevision,
        maxCount: Int
    ): Chunk[RecordedEvent] < (Sync & Abort[JournalError]) =
        state.use { current =>
            if maxCount <= 0 then Chunk.empty
            else
                val events = current.streams.getOrElse(streamId, Chunk.empty)
                if from.value >= events.length.toLong then Chunk.empty
                else events.drop(from.value.toInt).take(maxCount)
        }

    def streamInfo(streamId: StreamId): StreamInfo < (Sync & Abort[JournalError]) =
        state.use(current => info(current.streams.getOrElse(streamId, Chunk.empty)))

    private def modify[A](
        operation: State => Either[JournalError, (State, A)]
    ): A < (Sync & Abort[JournalError]) =
        state.get.flatMap { current =>
            operation(current) match
                case Left(error) =>
                    Abort.fail(error)
                case Right((next, value)) =>
                    state.compareAndSet(current, next).flatMap {
                        case true  => value
                        case false => modify(operation)
                    }
        }

    private def appendToState(
        current: State,
        streamId: StreamId,
        expected: ExpectedRevision,
        events: Chunk[EventEnvelope]
    ): Either[JournalError, (State, AppendResult)] =
        val currentEvents = current.streams.getOrElse(streamId, Chunk.empty)
        val currentInfo   = info(currentEvents)

        if !matches(expected, currentInfo) then Left(JournalError.Conflict(streamId, expected, currentInfo))
        else
            val firstValue = currentEvents.length.toLong
            val recorded = Chunk.from(
                events.toSeq.zipWithIndex.map { case (event, index) =>
                    RecordedEvent(
                        streamId = streamId,
                        revision = revision(firstValue + index.toLong),
                        eventId = event.id,
                        eventType = event.eventType,
                        payload = event.payload,
                        metadata = event.metadata
                    )
                }
            )
            val updatedEvents = currentEvents ++ recorded
            val nextInfo      = info(updatedEvents)
            val result = AppendResult(
                streamId = streamId,
                firstRevision = revision(firstValue),
                lastRevision = revision(firstValue + events.length.toLong - 1L),
                streamInfo = nextInfo
            )

            Right(current.copy(streams = current.streams.updated(streamId, updatedEvents)) -> result)

    private def matches(expected: ExpectedRevision, actual: StreamInfo): Boolean =
        expected match
            case ExpectedRevision.Any =>
                true
            case ExpectedRevision.NoStream =>
                actual == StreamInfo.Absent
            case ExpectedRevision.Exact(revision) =>
                actual match
                    case StreamInfo.Existing(_, lastRevision) => lastRevision == revision
                    case StreamInfo.Absent                    => false

    private def info(events: Chunk[RecordedEvent]): StreamInfo =
        if events.isEmpty then StreamInfo.Absent
        else StreamInfo.Existing(events.length.toLong, revision(events.length.toLong - 1L))

    private def revision(value: Long): StreamRevision =
        StreamRevision(value).getOrElse(throw IllegalStateException(s"invalid stream revision: $value"))
end InMemoryJournal
