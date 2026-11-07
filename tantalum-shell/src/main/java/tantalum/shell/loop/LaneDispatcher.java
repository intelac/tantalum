package tantalum.shell.loop;

import com.lmax.disruptor.EventHandler;
import java.time.Duration;
import java.util.List;
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
 * 每个用户另有上限（{@link UserPermits}）：一个用户在途的事件到了上限，他之后的事件停在那里，别的用户照常。
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
    private final UserPermits users;
    private final Sequence sequence;
    private final UserLane.Writer writer;
    private final Downstream cleanroom;
    private final UserLane.Loader loader;
    private final Residency residency;
    /** 消费者线程放事件，cleanroom 线程放处理完的步骤。 */
    private final Map<Principal, UserLane> lanes = new ConcurrentHashMap<>();
    private volatile boolean halted;
    private volatile Runnable onHalt = () -> { };

    /** {@code capacity} 是放行名额总数，{@code maxUserPermits} 是每个用户的上限（{@code tantalum.loop.max-user-permits}）。 */
    public LaneDispatcher(int capacity, int maxUserPermits, Sequence sequence, UserLane.Writer writer, Downstream cleanroom,
                             UserLane.Loader loader, Residency residency) {
        this.loader = loader;
        this.residency = residency;
        this.capacity = capacity;
        this.permits = new Semaphore(capacity);
        this.users = new UserPermits(maxUserPermits, permits, envelope -> lane(envelope.principal()).offer(envelope));
        this.sequence = sequence;
        this.writer = writer;
        this.cleanroom = cleanroom;
    }

    @Override
    public void onEvent(InputRing.Slot slot, long sequenceNumber, boolean endOfBatch) throws InterruptedException {
        EventEnvelope envelope = slot.envelope;
        slot.envelope = null;
        if (!users.take(envelope)) {
            return;
        }
        try {
            while (!halted) {
                if (permits.tryAcquire(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    lane(envelope.principal()).offer(envelope);
                    return;
                }
            }
        } catch (InterruptedException stopping) {
            // 关机：这个用户的名额退回，事件不进通道（review-fixes 需求 2.5）
            users.giveBack(envelope);
            throw stopping;
        }
        users.giveBack(envelope);
    }

    private UserLane lane(Principal principal) {
        return lanes.computeIfAbsent(principal, this::newLane);
    }

    private UserLane newLane(Principal principal) {
        return new UserLane(principal, sequence, writer, this, loader, residency);
    }

    /** 处理完的一步交给这个用户的通道：和他的事件同一个写入者，结果行落盘后放行。 */
    @Override
    public void release(EventEnvelope envelope, StepRecord record, Runnable afterDurable, Runnable done) {
        lane(envelope.principal()).offer(new UserLane.Released(envelope, record, afterDurable, done));
    }

    /** 一个事件走完全程（cleanroom 处理完，结果落盘、命令放行）后调用：归还放行名额和这个用户的名额。 */
    public void released(EventEnvelope envelope) {
        permits.release();
        users.released(envelope);
    }

    /** 等已接收的事件全部经过 cleanroom。超时返回 false。停在用户上限外的事件还没占放行名额，也要等它们。 */
    public boolean awaitIdle(Duration wait) throws InterruptedException {
        long deadline = System.currentTimeMillis() + wait.toMillis();
        while (users.parkedCount() > 0) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(POLL_MILLIS / 10);
        }
        long left = Math.max(0, deadline - System.currentTimeMillis());
        if (!permits.tryAcquire(capacity, left, TimeUnit.MILLISECONDS)) {
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
        // 停在用户上限外的事件不会再被放进去：丢弃并归还名额，awaitIdle 不再空转（需求 2.6）
        users.discardAll(this::discarded);
        onHalt.run();
    }

    /** 管线停住时做的事（关掉在途回执等），只设一次；默认什么都不做。 */
    public void onHalt(Runnable action) {
        this.onHalt = action;
    }

    @Override
    public void discarded(List<EventEnvelope> events) {
        for (EventEnvelope envelope : events) {
            released(envelope);
        }
    }
}
