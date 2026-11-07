package tantalum.shell.loop;

import com.lmax.disruptor.EventHandler;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.kernel.Kernel;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.shell.trace.TraceRecord;
import tantalum.value.command.Command;
import tantalum.value.decision.Decision;
import tantalum.value.decision.Fact;
import tantalum.value.decision.StepRecord;
import tantalum.value.decision.PhaseChanged;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.state.KernelState;
import tantalum.value.state.PrincipalState;

/**
 * Cleanroom Ring 的消费者：按序调内核，算出这一步的结果行，交给 {@link Release}：结果行落盘后才发布命令、事实、条目和轨迹记录的变化
 *。内存里的状态视图和投影立即更新。只看到已提交的事件，不做 I/O，不等待。
 */
public final class Cleanroom implements EventHandler<CleanroomRing.Slot> {

    private static final Logger LOG = LoggerFactory.getLogger(Cleanroom.class);
    /** 载入失败时推给客户端的状态名，让界面结束等待。 */
    public static final String LOAD_FAILED = "LoadFailed";

    private final Kernel kernel;
    private final BooleanSupplier halted;
    private final Predicate<Command> output;
    private final Projections projections;
    private final FactListener facts;
    private final StateView view;
    private final ResultPublisher results;
    private final StepListener steps;
    private final Evictor evictor;
    private final Release release;
    private final Consumer<EventEnvelope> processed;
    private KernelState state;
    /** 内核或状态发布抛出后置位：之后的事件全部跳过，入口返回 503，而不是让消费者线程静默死掉。 */
    private volatile boolean broken;

    public Cleanroom(Kernel kernel, BooleanSupplier halted, Predicate<Command> output,
                     Projections projections, FactListener facts, StateView view, ResultPublisher results, StepListener steps, Evictor evictor) {
        this(kernel, halted, output, projections, facts, view, results, steps, evictor, Release.IMMEDIATE, envelope -> { });
    }

    /** {@code processed} 在一个事件走完全程（结果落盘、命令放行，或者根本没交给内核）后调用。 */
    public Cleanroom(Kernel kernel, BooleanSupplier halted, Predicate<Command> output,
                     Projections projections, FactListener facts, StateView view, ResultPublisher results, StepListener steps, Evictor evictor,
                     Release release, Consumer<EventEnvelope> processed) {
        this.release = release;
        this.processed = processed;
        this.steps = steps;
        this.evictor = evictor;
        this.kernel = kernel;
        this.state = KernelState.empty();
        this.halted = halted;
        this.output = output;
        this.projections = projections;
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
        boolean handed = false;
        try {
            if (live) {
                handed = process(envelope, usage, load, endOfBatch);
            }
        } catch (Throwable failure) {
            broken = true;
            LOG.error("cleanroom broken at seq {}: refusing all further events", envelope.seq(), failure);
        }
        // 会话不再钉住，可以回收：还在排队的结果行由同一个通道先写、后载入，再载入时一定读得到它
        evictor.finished(envelope.session());
        if (!handed) {
            processed.accept(envelope);
        }
        if (live && !broken) {
            sweep();
        }
    }

    /** 返回 true 表示这一步交给了 {@link Release}，由它在最后调 {@code processed}。 */
    private boolean process(EventEnvelope envelope, PrincipalState usage, CleanroomRing.Load load, boolean endOfBatch) {
        if (usage != null && !state.principals().containsKey(envelope.principal())) {
            state = state.with(usage);
        }
        boolean handed = switch (load) {
            case CleanroomRing.Failed failed -> {
                rejected(envelope, failed.reason());
                yield false;
            }
            case CleanroomRing.Loaded loaded -> {
                merge(envelope, loaded);
                yield step(envelope);
            }
            case null -> step(envelope);
        };
        if (endOfBatch) {
            results.flushDropped();
        }
        return handed;
    }

    /** 不调内核：历史没载入，内核会把这个事件当成新会话的第一个。事件已写入、没有结果行，下次启动时和崩溃留下的尾部一样处理。 */
    private void rejected(EventEnvelope envelope, String reason) {
        LOG.warn("dropping seq {} for session {}: {}", envelope.seq(), envelope.session().value(), reason);
        // 属于已有运行的事件也报一条：那个运行在等的结果丢了，界面要看得到
        RunId run = envelope.run() != null ? envelope.run() : RunId.of(envelope.session(), envelope.seq());
        notifyListener(envelope.seq(), 0, new PhaseChanged(new RunRef(envelope.principal(), envelope.session(), run), LOAD_FAILED));
    }

    private void merge(EventEnvelope envelope, CleanroomRing.Loaded loaded) {
        SessionSlice slice = loaded.slice();
        state = state.withResident(slice.session(), slice.runs());
        projections.restore(envelope.session(), slice);
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

    private boolean step(EventEnvelope envelope) {
        long seq = envelope.seq();
        KernelState before = state;
        Decision decision = kernel.step(before, envelope);
        StepRecord record = StepRecord.of(envelope, before, decision);
        state = decision.newState();
        view.publish(state);
        KernelState after = state;
        Projections.Output projected = projections.apply(envelope, before, decision);
        evictor.used(envelope.session());
        release.release(envelope, record, () -> {
            // 索引也等落盘之后：事件只写了没 fsync，崩溃会丢，索引不能比文件超前（比如提交去重）
            notifyListener(envelope, before, after);
            for (Command command : decision.commands()) {
                if (!output.test(command)) {
                    results.reportDropped(command, "output ring full");
                }
            }
            int index = 0;
            for (Fact fact : decision.facts()) {
                notifyListener(seq, index++, fact);
            }
            for (TranscriptItem item : projected.items()) {
                notifyListener(seq, index++, envelope, item);
            }
            for (TraceRecord traced : projected.records()) {
                notifyListener(seq, index++, envelope, traced);
            }
        }, () -> processed.accept(envelope));
        return true;
    }

    private void notifyListener(EventEnvelope envelope, KernelState before, KernelState after) {
        try {
            steps.onStep(envelope, before, after);
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

    private void notifyListener(long seq, int index, EventEnvelope envelope, TraceRecord record) {
        try {
            facts.onRecord(seq, index, envelope.principal(), record);
        } catch (RuntimeException failure) {
            LOG.error("trace listener failed", failure);
        }
    }
}
