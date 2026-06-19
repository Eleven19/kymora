package io.eleven19.kymora.workflow.internal

import io.eleven19.kymora.workflow.*
import kyo.*

final private[workflow] class HubWorkflowTelemetry private (
    hub: Hub[WorkflowEvent],
    stateRef: SignalRef[WorkflowRunState],
    serial: Meter
) extends WorkflowTelemetry:

    override def publish(event: WorkflowEvent)(using Frame): Unit < Async =
        Abort.recover[Closed](_ => ())(serial.run {
            for
                published <- Abort.run[Closed](hub.put(event))
                _ <- published match
                    case Result.Success(_) =>
                        stateRef.updateAndGet(_.applyEvent(event)).unit
                    case Result.Failure(_) =>
                        Kyo.unit
                    case Result.Panic(ex) =>
                        Abort.panic(ex)
            yield ()
        })

    override def snapshot(using Frame): WorkflowRunState < Async =
        stateRef.current

    override def state(using Frame): Signal[WorkflowRunState] < Sync =
        stateRef

    override def listen(bufferSize: Int = WorkflowTelemetry.DefaultBufferSize)(using
        Frame
    ): Hub.Listener[WorkflowEvent] < (Sync & Scope & Abort[Closed]) =
        hub.listen(bufferSize)

end HubWorkflowTelemetry

private[workflow] object HubWorkflowTelemetry:

    def init(bufferSize: Int)(using Frame): WorkflowTelemetry < (Async & Scope) =
        for
            hub      <- Hub.init[WorkflowEvent](bufferSize)
            stateRef <- Signal.initRef(WorkflowRunState.empty)
            serial   <- Meter.initMutexUnscoped
        yield HubWorkflowTelemetry(hub, stateRef, serial)
end HubWorkflowTelemetry
