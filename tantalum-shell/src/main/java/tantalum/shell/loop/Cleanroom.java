package tantalum.shell.loop;

import com.lmax.disruptor.EventHandler;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.kernel.Kernel;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.value.command.Command;
import tantalum.value.decision.Decision;
import tantalum.value.decision.Fact;
import tantalum.value.event.EventEnvelope;
import tantalum.value.state.KernelState;

/** Cleanroom Ring 的消费者：按序调内核，发布命令、事实和条目变化。只看到已提交的事件，不做 I/O，不等待。 */
public final class Cleanroom implements EventHandler<InputRing.Slot> {

    private static final Logger LOG = LoggerFactory.getLogger(Cleanroom.class);

    private final Kernel kernel;
    private final BooleanSupplier halted;
    private final Predicate<Command> output;
    private final Projection projection;
    private final FactListener facts;
    private final StateView view;
    private final ResultPublisher results;
    private final StepListener steps;
    private KernelState state;
    /** 内核或状态发布抛出后置位：之后的事件全部跳过，入口返回 503，而不是让消费者线程静默死掉。 */
    private volatile boolean broken;

    public Cleanroom(Kernel kernel, KernelState initial, BooleanSupplier halted, Predicate<Command> output,
                     Projection projection, FactListener facts, StateView view, ResultPublisher results) {
        this(kernel, initial, halted, output, projection, facts, view, results, StepListener.NONE);
    }

    public Cleanroom(Kernel kernel, KernelState initial, BooleanSupplier halted, Predicate<Command> output,
                     Projection projection, FactListener facts, StateView view, ResultPublisher results, StepListener steps) {
        this.steps = steps;
        this.kernel = kernel;
        this.state = initial;
        this.halted = halted;
        this.output = output;
        this.projection = projection;
        this.facts = facts;
        this.view = view;
        this.results = results;
    }

    /** 内核自己不会抛出（{@code Kernel.step} 把处理函数的异常变成运行失败）；这里防的是内核之外的 bug 和 {@code Error}。 */
    public boolean broken() {
        return broken;
    }

    @Override
    public void onEvent(InputRing.Slot slot, long sequence, boolean endOfBatch) {
        if (halted.getAsBoolean() || broken) {
            slot.envelope = null;
            return;
        }
        EventEnvelope envelope = slot.envelope;
        slot.envelope = null;
        try {
            step(envelope, endOfBatch);
        } catch (Throwable failure) {
            broken = true;
            LOG.error("cleanroom broken at seq {}: refusing all further events", envelope.seq(), failure);
        }
    }

    private void step(EventEnvelope envelope, boolean endOfBatch) {
        long seq = envelope.seq();
        KernelState before = state;
        Decision decision = kernel.step(before, envelope);
        state = decision.newState();
        view.publish(state);
        notifyListener(envelope, before);
        for (Command command : decision.commands()) {
            if (!output.test(command)) {
                results.reportDropped(command, "output ring full");
            }
        }
        int index = 0;
        for (Fact fact : decision.facts()) {
            notifyListener(seq, index++, fact);
        }
        for (TranscriptItem item : project(envelope, decision)) {
            notifyListener(seq, index++, envelope, item);
        }
        if (endOfBatch) {
            results.flushDropped();
        }
    }

    /** 投影出错只丢这次的条目，环继续。 */
    private List<TranscriptItem> project(EventEnvelope envelope, Decision decision) {
        try {
            return projection.apply(envelope, decision);
        } catch (RuntimeException failure) {
            LOG.error("projection failed at seq {}", envelope.seq(), failure);
            return List.of();
        }
    }

    private void notifyListener(EventEnvelope envelope, KernelState before) {
        try {
            steps.onStep(envelope, before, state);
        } catch (RuntimeException failure) {
            LOG.error("step listener failed at seq {}", envelope.seq(), failure);
        }
    }

    private void notifyListener(long seq, int index, Fact fact) {
        try {
            facts.onFact(seq, index, fact);
        } catch (RuntimeException failure) {
            LOG.error("fact listener failed", failure);
        }
    }

    private void notifyListener(long seq, int index, EventEnvelope envelope, TranscriptItem item) {
        try {
            facts.onItem(seq, index, envelope.principal(), item);
        } catch (RuntimeException failure) {
            LOG.error("item listener failed", failure);
        }
    }
}
