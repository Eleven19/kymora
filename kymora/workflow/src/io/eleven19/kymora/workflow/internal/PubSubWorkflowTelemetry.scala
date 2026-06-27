package io.eleven19.kymora.workflow.internal

import io.eleven19.kymora.workflow.*
import kyo.*

final private[workflow] class PubSubWorkflowTelemetry private (
    pubSub: PubSub[WorkflowEvent],
    stateRef: SignalRef[WorkflowRunState],
    publisher: Actor[Closed, PubSubWorkflowTelemetry.Command, Unit]
) extends WorkflowTelemetry:

    import PubSubWorkflowTelemetry.*

    override def publish(event: WorkflowEvent)(using Frame): Unit < Async =
        Abort.recover[Closed](_ => Kyo.unit)(publisher.ask(Command.Publish(event, _)))

    override def snapshot(using Frame): WorkflowRunState < Async =
        stateRef.current

    override def state(using Frame): Signal[WorkflowRunState] < Sync =
        stateRef

    override def subscribe(bufferSize: Int = WorkflowTelemetry.DefaultBufferSize)(using
        Frame
    ): WorkflowTelemetry.Subscription < (Async & Scope & Abort[Closed]) =
        for
            channel <- Channel.init[WorkflowEvent](bufferSize)
            _       <- pubSub.subscribe(Actor.Subject.init(channel))
        yield ChannelSubscription(channel)
end PubSubWorkflowTelemetry

private[workflow] object PubSubWorkflowTelemetry:

    private enum Command:
        case Publish(event: WorkflowEvent, replyTo: Actor.Subject[Unit])

    private final class ChannelSubscription(channel: Channel[WorkflowEvent]) extends WorkflowTelemetry.Subscription:

        override def take(using Frame): WorkflowEvent < (Async & Abort[Closed]) =
            channel.take

        override def takeExactly(n: Int)(using Frame): Chunk[WorkflowEvent] < (Async & Abort[Closed]) =
            channel.takeExactly(n)

        override def drainUpTo(max: Int)(using Frame): Chunk[WorkflowEvent] < (Sync & Abort[Closed]) =
            channel.drainUpTo(max)
    end ChannelSubscription

    def init(bufferSize: Int)(using Frame): WorkflowTelemetry < (Async & Scope) =
        for
            pubSub   <- PubSub.linearized[WorkflowEvent]
            stateRef <- Signal.initRef(WorkflowRunState.empty)
            publisher <- Actor.run(capacity = bufferSize) {
                Actor.receiveLoop[Command] {
                    case Command.Publish(event, replyTo) =>
                        pubSub
                            .publish(event)
                            .andThen(stateRef.updateAndGet(_.applyEvent(event)).unit)
                            .andThen(replyTo.send(()))
                            .andThen(Loop.continue)
                }
            }
        yield PubSubWorkflowTelemetry(pubSub, stateRef, publisher)
end PubSubWorkflowTelemetry
