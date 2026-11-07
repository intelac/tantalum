package tantalum.shell.loop;

import java.util.List;

import java.util.ArrayList;

import java.util.function.Consumer;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.RunInterrupted;
import tantalum.value.event.TimerFired;
import tantalum.value.id.Principal;

/**
 * 每个用户在途事件的上限（ops-hardening-2 需求 4、D4）。在途是指取了放行名额、还没过 cleanroom 的事件。到上限后这个用户之后的事件
 * 停在这里，不占放行名额，输入环的消费者继续取别人的；他每处理完一个事件，就按原顺序放一个进去。重启收尾（{@link RunInterrupted}）
 * 和定时器（{@link TimerFired}）不计入上限、只占放行名额，但排在停住的事件后面，保持这个用户的事件顺序。
 * 一个用户的锁只在他自己的事件进出时拿，不同用户互不等待。
 */
final class UserPermits {

    /** 放进用户通道。在用户的锁里调用，必须不阻塞。 */
    @FunctionalInterface
    interface Admit {
        void admit(EventEnvelope envelope);
    }

    private static final class Account {
        int inFlight;
        final ArrayDeque<EventEnvelope> waiting = new ArrayDeque<>();
    }

    private final int limit;
    private final Semaphore permits;
    private final Admit admit;
    private final Map<Principal, Account> accounts = new ConcurrentHashMap<>();
    /** 有事件停住的用户：别的用户归还放行名额时也替他们试一次。 */
    private final Set<Principal> parkedUsers = ConcurrentHashMap.newKeySet();
    private final AtomicInteger parked = new AtomicInteger();

    /** {@code permits} 是放行名额（总数等于 cleanroom 环的大小）；停住的事件放进去时从这里取。 */
    UserPermits(int limit, Semaphore permits, Admit admit) {
        this.limit = limit;
        this.permits = permits;
        this.admit = admit;
    }

    /** 计入用户上限的事件：除了重启收尾和定时器。 */
    static boolean counted(EventEnvelope envelope) {
        Event event = envelope.event();
        return !(event instanceof TimerFired || event instanceof RunInterrupted);
    }

    /**
     * 消费者线程：这个用户还有名额就记一个（返回 true，调用方再取放行名额并放行）；没有、或者他已有事件停住，就排在后面（返回 false）。
     */
    boolean take(EventEnvelope envelope) {
        Account account = account(envelope.principal());
        synchronized (account) {
            boolean counted = counted(envelope);
            if (!account.waiting.isEmpty() || (counted && account.inFlight >= limit)) {
                account.waiting.add(envelope);
                parked.incrementAndGet();
                parkedUsers.add(envelope.principal());
                return false;
            }
            if (counted) {
                account.inFlight++;
            }
            return true;
        }
    }

    /** 调用方取放行名额时停机了：退回 {@link #take} 记下的名额。 */
    void giveBack(EventEnvelope envelope) {
        if (!counted(envelope)) {
            return;
        }
        Account account = account(envelope.principal());
        synchronized (account) {
            account.inFlight--;
        }
    }

    /**
     * 一个事件过完 cleanroom（或被丢弃），放行名额已归还：归还他的用户名额，把停住的事件按顺序放进去，直到名额或放行名额用完。
     * 放行名额刚多了一个，别的用户停住的事件可能正等它，也替他们试一次。
     */
    void released(EventEnvelope envelope) {
        Account account = account(envelope.principal());
        synchronized (account) {
            if (counted(envelope)) {
                account.inFlight--;
            }
            drain(envelope.principal(), account);
        }
        if (parked.get() > 0) {
            for (Principal other : parkedUsers) {
                Account waiting = account(other);
                synchronized (waiting) {
                    drain(other, waiting);
                }
            }
        }
    }

    /** 停住的事件数，{@code LaneDispatcher.awaitIdle} 用：它们还没占放行名额。 */
    int parkedCount() {
        return parked.get();
    }

    /** 管线停住：停在上限外的事件不会再被放进去，全部交给 {@code discard}（review-fixes 需求 2.6）。 */
    void discardAll(Consumer<List<EventEnvelope>> discard) {
        List<EventEnvelope> dropped = new ArrayList<>();
        for (Principal principal : List.copyOf(parkedUsers)) {
            Account account = account(principal);
            synchronized (account) {
                while (!account.waiting.isEmpty()) {
                    dropped.add(account.waiting.poll());
                    parked.decrementAndGet();
                }
                parkedUsers.remove(principal);
            }
        }
        if (!dropped.isEmpty()) {
            discard.accept(dropped);
        }
    }

    private void drain(Principal principal, Account account) {
        while (!account.waiting.isEmpty()) {
            EventEnvelope head = account.waiting.peek();
            boolean counted = counted(head);
            if (counted && account.inFlight >= limit) {
                return;
            }
            if (!permits.tryAcquire()) {
                return;
            }
            account.waiting.poll();
            parked.decrementAndGet();
            if (counted) {
                account.inFlight++;
            }
            admit.admit(head);
        }
        parkedUsers.remove(principal);
    }

    private Account account(Principal principal) {
        return accounts.computeIfAbsent(principal, ignored -> new Account());
    }
}
