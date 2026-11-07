package tantalum.shell.loop;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.TimerFired;

/**
 * 重启后替等用户回答的运行把期限的定时器重新起来。运行中进入等人的定时器是 {@code StartTimer} 命令，
 * 由 {@code TimerHandler} 的线程等；进程换过以后那些线程没了，这里按索引里记的到期时刻再等一次，到时发 {@code TimerFired}：
 * 内核认出它是那个问题的定时器就按“没有回答”结算，已经答过的被忽略。已经过期的立刻发。
 */
public final class DeadlineTimers implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DeadlineTimers.class);
    private static final Duration PUBLISH_RETRY = Duration.ofMillis(10);

    private final Clock clock;
    private final Predicate<EventEnvelope> publish;
    private final List<Thread> timers = new ArrayList<>();

    /** {@code publish} 把事件放进输入环，放不进去返回 false（环满），这里隔一会儿再试。 */
    public DeadlineTimers(Clock clock, Predicate<EventEnvelope> publish) {
        this.clock = clock;
        this.publish = publish;
    }

    public synchronized void arm(List<AnswerDeadline> deadlines) {
        for (AnswerDeadline deadline : deadlines) {
            Thread timer = Thread.ofVirtual().name("deadline-" + deadline.session().value()).unstarted(() -> fire(deadline));
            timers.add(timer);
            timer.start();
        }
        if (!deadlines.isEmpty()) {
            LOG.info("re-armed {} answer deadline(s) left by the previous process", deadlines.size());
        }
    }

    private void fire(AnswerDeadline deadline) {
        try {
            Duration left = Duration.between(clock.instant(), deadline.deadline());
            if (!left.isNegative() && !left.isZero()) {
                Thread.sleep(left);
            }
            EventEnvelope envelope = new EventEnvelope(EventEnvelope.UNASSIGNED, clock.instant(), deadline.principal(),
                    deadline.session(), deadline.run(), new TimerFired(deadline.timer()));
            while (!publish.test(envelope)) {
                Thread.sleep(PUBLISH_RETRY);
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void close() {
        timers.forEach(Thread::interrupt);
        timers.clear();
    }
}
