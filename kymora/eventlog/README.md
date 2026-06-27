# kymora-eventlog

`kymora-eventlog` provides event journaling as a first-class Kyo effect.
Programs depend on the `Journal` effect, and callers provide a concrete
`Journal.Backend` at the edge of the program. The built-in backend is an
ephemeral in-memory journal.

The module is published for JVM, Scala.js, Scala.js WASM, and Scala Native.
WASM tests run on Node.js 24+.

## Overview

- `Journal` is the effect for appending events, reading stream slices, and
  inspecting stream state.
- `Journal.Backend` is the storage contract behind the effect. Backends provide
  `append`, `read`, and `streamInfo` under `Sync` and `Abort[JournalError]`.
- `EventLog.inMemory.init` creates a fresh in-memory backend. Separate calls do
  not share streams.
- `StreamId`, `EventId`, and `EventType` are validated opaque identifiers.
  Their constructors return `Result[JournalError.InvalidIdentifier, A]`.
- `EventEnvelope` is the input event. `RecordedEvent` is the stored event
  returned by reads.
- `StreamRevision` is the zero-based event position. `StreamVersion` is the
  one-based count/after-last view.
- `ExpectedRevision` controls optimistic append checks with `Any`, `NoStream`,
  or `Exact(revision)`.
- `EventMetadata`, `MetadataKey`, and `MetadataValue` carry structural metadata.
- `JournalError` is the recoverable error model used with
  `Abort[JournalError]`.

## Basic Usage

```scala
import io.eleven19.kymora.eventlog.*
import kyo.*

def valid[A](result: Result[JournalError.InvalidIdentifier, A]): A =
  result.getOrElse(throw AssertionError("expected valid eventlog identifier"))

val streamId = valid(StreamId("users-1"))
val event = EventEnvelope(
  id = valid(EventId("event-1")),
  eventType = valid(EventType("UserCreated")),
  payload = Span.from("""{"name":"Ada"}""".getBytes("UTF-8")),
  metadata = EventMetadata.empty
)

val appended =
  for
    backend <- EventLog.inMemory.init
    result <- Journal.run(backend):
                Journal.append(streamId, ExpectedRevision.NoStream, Chunk(event))
  yield result
```

`Journal.run(backend)` handles the `Journal` effect by delegating to the
provided backend. The handled program still uses `Sync` and
`Abort[JournalError]` because backend operations can perform effects and fail
with recoverable journal errors.

## Append Concurrency

Appends use optimistic concurrency through `ExpectedRevision`:

- `ExpectedRevision.NoStream` succeeds only when the stream is absent.
- `ExpectedRevision.Any` skips the revision check.
- `ExpectedRevision.Exact(revision)` succeeds only when the stream exists and
  its current last revision is exactly `revision`.

A conflict fails with `JournalError.Conflict(streamId, expected, actual)`, where
`actual` is the current `StreamInfo`. Appending an empty `Chunk[EventEnvelope]`
fails with `JournalError.EmptyAppend`.

A successful append assigns consecutive zero-based `StreamRevision` values.
The first event in a new stream is revision `0`; a two-event first append gets
revisions `0` and `1`. `AppendResult.firstRevision` and
`AppendResult.lastRevision` cover the appended batch, and
`AppendResult.streamInfo` reports the post-append stream state.

## Reading Streams

`Journal.read(streamId, from, maxCount)` returns stored records from the
requested zero-based revision, bounded by `maxCount`.

Missing streams return `Chunk.empty`. A non-positive `maxCount` returns
`Chunk.empty`. Reading from a revision at or beyond the current event count also
returns `Chunk.empty`.

`Journal.streamInfo(streamId)` returns `StreamInfo.Absent` for a missing stream
or `StreamInfo.Existing(eventCount, lastRevision)` for a stream with events.
There is no public stream creation operation separate from append.

## Event Data And Metadata

The journal stores raw `Span[Byte]` payloads and structural metadata. Schemas,
codecs, serialization formats, and domain event ADTs belong above this layer.
This module does not provide typed event codecs, projections, snapshots,
subscriptions, global reads, durable adapters, or transactional multi-stream
appends.

Metadata keys are dotted paths, not nested maps by themselves. Valid examples
include `session.id`, `trace.correlation_id`, and `event.number`; invalid keys
include `""`, `.foo`, `foo.`, and `foo..bar`.

`MetadataValue` mirrors `kyo.Structure.Value` with records, variant cases,
sequences, map entries, strings, booleans, integers, decimals, big numbers, and
null. Use `value.toStructure` and `MetadataValue.fromStructure(value)` to
convert losslessly to and from `Structure.Value`.

## Backends

```scala
val memory: Journal.Backend < Sync =
  EventLog.inMemory.init
```

The in-memory backend is scoped to the backend value returned by
`EventLog.inMemory.init`. It is useful for tests and local programs, but it is
ephemeral and does not share state across separate `init` calls.

Implement `Journal.Backend` to connect `Journal` programs to another storage
engine while preserving the same append, read, stream-info, and error
semantics.

## Error Handling

Journal operations fail through `Abort[JournalError]`, not by throwing ordinary
exceptions:

- `JournalError.EmptyAppend` means an append was attempted with no events.
- `JournalError.Conflict` means the expected revision did not match the current
  stream state.
- `JournalError.InvalidIdentifier` means a `StreamId`, `EventId`, `EventType`,
  `StreamRevision`, or `StreamVersion` value failed validation.
