package io.eleven19.kymora.eventlog

enum JournalError derives CanEqual:
    case EmptyAppend
    case Conflict(streamId: StreamId, expected: ExpectedRevision, actual: StreamInfo)
    case InvalidIdentifier(kind: String, value: String)
end JournalError
