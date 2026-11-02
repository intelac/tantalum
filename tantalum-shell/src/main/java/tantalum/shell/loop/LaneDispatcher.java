package tantalum.shell.loop;

import com.lmax.disruptor.EventHandler;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.journal.Sequence;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;

/**
 * 输入环的唯一消费者：取一个放行名额，把事件放进该用户的通道。名额总数等于 Cleanroom Ring 的大小，
 * 所以那个环不会满，已落盘的事件不会丢。名额用完时这里等待，输入环随之填满，入口返回 429。
 */
public final class LaneDispatcher implements EventHandler<InputRing.Slot>, UserLane.Exit {

    private static final Logger LOG = LoggerFactory.getLogger(LaneDispatcher.class);
    private static final long POLL_MILLIS = 100;

    private final int capacity;
    private final Semaphore permits;
    private final Sequence sequence;
    private final UserLane.Writer writer;
    private final BiPredicate<EventEnvelope, Preload> cleanroom;
    private final UserLane.Loader loader;
    private final Residency residency;
    /** 只有消费者线程访问。 */
    private final Map<Principal, UserLane> lanes = new HashMap<>();
    private volatile boolean halted;

    public LaneDispatcher(int capacity, Sequence sequence, UserLane.Writer writer, BiPredicate<EventEnvelope, Preload> cleanroom,
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

    /** cleanroom 处理完一个事件后调用。 */
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
    public boolean publish(EventEnvelope envelope, Preload preload) {
        return cleanroom.test(envelope, preload);
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
