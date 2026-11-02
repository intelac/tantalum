package tantalum.shell.loop;

import com.lmax.disruptor.EventHandler;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.kernel.Kernel;
import tantalum.shell.conversation.Projection;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.value.command.Command;
import tantalum.value.decision.Decision;
import tantalum.value.decision.Fact;
import tantalum.value.decision.PhaseChanged;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.state.KernelState;
import tantalum.value.state.PrincipalState;

/** Cleanroom Ring 的消费者：按序调内核，发布命令、事实和条目变化。只看到已提交的事件，不做 I/O，不等待。 */
public final class Cleanroom implements EventHandler<CleanroomRing.Slot> {

    private static final Logger LOG = LoggerFactory.getLogger(Cleanroom.class);
    /** 载入失败时推给客户端的状态名，让界面结束等待。 */
    public static final String LOAD_FAILED = "LoadFailed";

    private final Kernel kernel;
    private final BooleanSupplier halted;
    private final Predicate<Command> output;
    private final Projection projection;
    private final FactListener facts;
    private final StateView view;
    private final ResultPublisher results;
    private final StepListener steps;
    private final Evictor evictor;
    private KernelState state;
    /** 内核或状态发布抛出后置位：之后的事件全部跳过，入口返回 503，而不是让消费者线程静默死掉。 */
    private volatile boolean broken;

    public Cleanroom(Kernel kernel, BooleanSupplier halted, Predicate<Command> output,
                     Projection projection, FactListener facts, StateView view, ResultPublisher results, StepListener steps, Evictor evictor) {
        this.steps = steps;
        this.evictor = evictor;
        this.kernel = kernel;
        this.state = KernelState.empty();
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
    public void onEvent(CleanroomRing.Slot slot, long sequence, boolean endOfBatch) {
        EventEnvelope envelope = slot.envelope;
        PrincipalState usage = slot.usage;
        CleanroomRing.Load load = slot.load;
        slot.envelope = null;
        slot.usage = null;
        slot.load = null;
        boolean live = !halted.getAsBoolean() && !broken;
        try {
            if (live) {
                process(envelope, usage, load, endOfBatch);
            }
        } catch (Throwable failure) {
            broken = true;
            LOG.error("cleanroom broken at seq {}: refusing all further events", envelope.seq(), failure);
        }
        evictor.finished(envelope.session());
        if (live && !broken) {
            sweep();
        }
    }

    private void process(EventEnvelope envelope, PrincipalState usage, CleanroomRing.Load load, boolean endOfBatch) {
        if (usage != null && !state.principals().containsKey(envelope.principal())) {
            state = state.with(usage);
        }
        switch (load) {
            case CleanroomRing.Failed failed -> rejected(envelope, failed.reason());
            case CleanroomRing.Loaded loaded -> {
                merge(envelope, loaded);
                step(envelope);
            }
            case null -> step(envelope);
        }
        if (endOfBatch) {
            results.flushDropped();
        }
    }

    /** 不调内核：历史没载入，内核会把这个事件当成新会话的第一个。事件已落盘，下次启动时以“因重启中断”收尾。 */
    private void rejected(EventEnvelope envelope, String reason) {
        LOG.warn("dropping seq {} for session {}: {}", envelope.seq(), envelope.session().value(), reason);
        if (envelope.run() == null) {
            RunRef run = new RunRef(envelope.principal(), envelope.session(), RunId.of(envelope.session(), envelope.seq()));
            notifyListener(envelope.seq(), 0, new PhaseChanged(run, LOAD_FAILED));
        }
    }

    private void merge(EventEnvelope envelope, CleanroomRing.Loaded loaded) {
        SessionSlice slice = loaded.slice();
        state = state.withResident(slice.session(), slice.runs());
        projection.restore(envelope.session(), slice.transcript());
        evictor.used(envelope.session());
    }

    private void sweep() {
        try {
            KernelState swept = evictor.sweep(state);
            if (swept != state) {
                state = swept;
                view.publish(state);
            }
        } catch (RuntimeException | Error failure) {
            broken = true;
            LOG.error("eviction failed: refusing all further events", failure);
        }
    }

    private void step(EventEnvelope envelope) {
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
        evictor.used(envelope.session());
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
