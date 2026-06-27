package io.eleven19.kymora.eventlog

import kyo.*

object EventLog:

    object inMemory:

        def init(using Frame): Journal.Backend < Sync =
            _root_.io.eleven19.kymora.eventlog.internal.InMemoryJournal.init
    end inMemory
end EventLog
