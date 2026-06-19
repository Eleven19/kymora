package io.eleven19.kymora.workflow

import kyo.*

trait WorkflowTelemetry:
    def publish(event: WorkflowEvent)(using Frame): Unit < Async

    def snapshot(using Frame): WorkflowRunState < Async =
        WorkflowRunState.empty

    def state(using Frame): Signal[WorkflowRunState] < Sync =
        Signal.initConst(WorkflowRunState.empty)

    def listen(bufferSize: Int = WorkflowTelemetry.DefaultBufferSize)(using
        frame: Frame
    ): Hub.Listener[WorkflowEvent] < (Sync & Scope & Abort[Closed]) =
        Abort.fail(Closed("WorkflowTelemetry.listen", frame))
end WorkflowTelemetry

object WorkflowTelemetry:
    val DefaultBufferSize: Int = 4096

    object NoOp extends WorkflowTelemetry:

        override def publish(event: WorkflowEvent)(using Frame): Unit < Async =
            ()
    end NoOp

    def fromObserver(observer: Observer): WorkflowTelemetry =
        new WorkflowTelemetry:
            override def publish(event: WorkflowEvent)(using Frame): Unit < Async =
                observer.onEvent(event)

    def live(bufferSize: Int = DefaultBufferSize)(using Frame): WorkflowTelemetry < (Async & Scope) =
        _root_.io.eleven19.kymora.workflow.internal.HubWorkflowTelemetry.init(bufferSize)
end WorkflowTelemetry
