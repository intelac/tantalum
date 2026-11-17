package tantalum.shell.loop;

import java.time.Clock;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.value.command.Command;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.RunRef;

/** Handler 写回结果的唯一入口。时间在这里写入事件，内核不取时间。 */
public final class ResultPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(ResultPublisher.class);

    private record Drop(Command command, String detail) {}

    private final InputRing input;
    private final InFlightCommands inFlight;
    private final Clock clock;
    private final int liveReserve;
    private final Queue<Drop> drops = new ConcurrentLinkedQueue<>();

    public ResultPublisher(InputRing input, InFlightCommands inFlight, Clock clock, int liveReserve) {
        this.input = input;
        this.inFlight = inFlight;
        this.clock = clock;
        this.liveReserve = liveReserve;
    }

    public void publish(Command command, Event result) {
        boolean aborted = inFlight.isAborted(command.id());
        if (aborted && input.remainingCapacity() <= liveReserve) {
            LOG.error("dropping late result of aborted command {}: no spare capacity", command.id().value());
            return;
        }
        if (input.tryPublish(envelope(command.run(), result))) {
            return;
        }
        if (aborted) {
            LOG.error("dropping late result of aborted command {}: input ring full", command.id().value());
        } else {
            reportDropped(command, "input ring full");
        }
    }

    /** 容量不变量被破坏。不等待：写不进去就先记下来，由 {@code Cleanroom} 在批尾补发。 */
    public void reportDropped(Command command, String detail) {
        LOG.error("capacity invariant broken for command {}: {}", command.id().value(), detail);
        drops.add(new Drop(command, detail));
        flushDropped();
    }

    /** 把记下的丢失写成事件，让受影响的运行以失败结束。 */
    public void flushDropped() {
        Drop drop = drops.poll();
        while (drop != null) {
            CommandDropped event = new CommandDropped(drop.command().id(), drop.detail());
            if (!input.tryPublish(envelope(drop.command().run(), event))) {
                drops.add(drop);
                return;
            }
            drop = drops.poll();
        }
    }

    public int pendingDrops() {
        return drops.size();
    }

    private EventEnvelope envelope(RunRef run, Event event) {
        return new EventEnvelope(EventEnvelope.UNASSIGNED, clock.instant(), run.principal(), run.session(), run.run(), event);
    }
}
