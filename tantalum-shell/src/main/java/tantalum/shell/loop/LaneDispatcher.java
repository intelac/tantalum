package tantalum.shell.loop;

import com.lmax.disruptor.EventHandler;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.journal.Sequence;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.state.PrincipalState;

/**
 * 输入环的唯一消费者：取一个放行名额，把事件放进该用户的通道。名额总数等于 Cleanroom Ring 的大小，
 * 所以那个环不会满，已写入的事件不会丢。名额用完时这里等待，输入环随之填满，入口返回 429。
 */
public final class LaneDispatcher implements EventHandler<InputRing.Slot>, UserLane.Exit, Release {

    /** 通道放行的去处，生产里是 {@link CleanroomRing#tryPublish}。环满时返回 false。 */
    @FunctionalInterface
    public interface Downstream {
        boolean publish(EventEnvelope envelope, PrincipalState usage, CleanroomRing.Load load);
    }

    private static final Logger LOG = LoggerFactory.getLogger(LaneDispatcher.class);
    private static final long POLL_MILLIS = 100;

    private final int capacity;
    private final Semaphore permits;
    private final Sequence sequence;
    private final UserLane.Writer writer;
    private final Downstream cleanroom;
    private final UserLane.Loader loader;
    private final Residency residency;
    /** 消费者线程放事件，cleanroom 线程放处理完的步骤。 */
    private final Map<Principal, UserLane> lanes = new ConcurrentHashMap<>();
    private volatile boolean halted;

    public LaneDispatcher(int capacity, Sequence sequence, UserLane.Writer writer, Downstream cleanroom,
                             UserLane.Loader loader, Residency residency) {
        this.loader = loader;
        this.residency = residency;
        this.capacity = capacity;
        this.permits = new Semaphore(capacity);
        this.sequence = sequence;
        this.writer = writer;
        this.cleanroom = cleanroom;
    }

    @Override
    public void onEvent(InputRing.Slot slot, long sequenceNumber, boolean endOfBatch) throws InterruptedException {
        EventEnvelope envelope = slot.envelope;
        slot.envelope = null;
        while (!halted) {
            if (permits.tryAcquire(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                lanes.computeIfAbsent(envelope.principal(), this::newLane).offer(envelope);
                return;
            }
        }
    }

    private UserLane newLane(Principal principal) {
        return new UserLane(principal, sequence, writer, this, loader, residency);
    }

    /** 处理完的一步交给这个用户的通道：和他的事件同一个写入者，结果行落盘后放行。 */
    @Override
    public void release(EventEnvelope envelope, StepRecord record, Runnable afterDurable, Runnable done) {
        lanes.computeIfAbsent(envelope.principal(), this::newLane).offer(new UserLane.Released(envelope, record, afterDurable, done));
    }

    /** 一个事件走完全程（cleanroom 处理完，结果落盘、命令放行）后调用。 */
    public void released() {
        permits.release();
    }

    /** 等已接收的事件全部经过 cleanroom。超时返回 false。 */
    public boolean awaitIdle(Duration wait) throws InterruptedException {
        if (!permits.tryAcquire(capacity, wait.toMillis(), TimeUnit.MILLISECONDS)) {
            return false;
        }
        permits.release(capacity);
        return true;
    }

    @Override
    public boolean halted() {
        return halted;
    }

    @Override
    public boolean publish(EventEnvelope envelope, PrincipalState usage, CleanroomRing.Load load) {
        return cleanroom.publish(envelope, usage, load);
    }

    @Override
    public void failed(Throwable failure) {
        halted = true;
        LOG.error("journal write failed; the process stops handling events and must be reopened", failure);
    }

    @Override
    public void discarded(int count) {
        permits.release(count);
    }
}
