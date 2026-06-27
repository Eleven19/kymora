package io.eleven19.kymora.workflow

import kyo.*

trait WorkflowTelemetry:
    def publish(event: WorkflowEvent)(using Frame): Unit < Async

    def snapshot(using Frame): WorkflowRunState < Async =
        WorkflowRunState.empty

    def state(using Frame): Signal[WorkflowRunState] < Sync =
        Signal.initConst(WorkflowRunState.empty)

    def subscribe(bufferSize: Int = WorkflowTelemetry.DefaultBufferSize)(using
        frame: Frame
    ): WorkflowTelemetry.Subscription < (Async & Scope & Abort[Closed]) =
        Abort.fail(Closed("WorkflowTelemetry.subscribe", frame))
end WorkflowTelemetry

object WorkflowTelemetry:
    val DefaultBufferSize: Int = 4096

    trait Subscription:
        def take(using Frame): WorkflowEvent < (Async & Abort[Closed])

        def takeExactly(n: Int)(using Frame): Chunk[WorkflowEvent] < (Async & Abort[Closed])

        def drainUpTo(max: Int)(using Frame): Chunk[WorkflowEvent] < (Sync & Abort[Closed])
    end Subscription

    object NoOp extends WorkflowTelemetry:

        override def publish(event: WorkflowEvent)(using Frame): Unit < Async =
            ()
    end NoOp

    def fromObserver(observer: Observer): WorkflowTelemetry =
        new WorkflowTelemetry:
            override def publish(event: WorkflowEvent)(using Frame): Unit < Async =
                observer.onEvent(event)

    def live(bufferSize: Int = DefaultBufferSize)(using Frame): WorkflowTelemetry < (Async & Scope) =
        _root_.io.eleven19.kymora.workflow.internal.PubSubWorkflowTelemetry.init(bufferSize)
end WorkflowTelemetry
