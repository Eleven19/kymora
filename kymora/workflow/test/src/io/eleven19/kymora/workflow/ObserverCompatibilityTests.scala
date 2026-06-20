package io.eleven19.kymora.workflow

import io.eleven19.kymora.workflow.testkit.CollectingObserver
import kyo.*
import kyo.test.*

class ObserverCompatibilityTests extends Test[Any]:

    "Observer.asTelemetry forwards published events to the observer" in {
        val event = WorkflowEvent.TaskQueued(TaskId("compile"))

        for
            observer <- CollectingObserver.init
            telemetry = observer.asTelemetry
            _      <- telemetry.publish(event)
            events <- observer.events
        yield assert(events == Chunk(event))
    }

end ObserverCompatibilityTests
