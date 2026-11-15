package tantalum.shell.boot;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToIntFunction;
import tantalum.shell.plugin.PluginServices;

/**
 * 插件调模型的排队（memory-final-plan 第 15 节）：每条路线有几个名额（本机路线一个），前台的（比如找回时改写问题，用户在等）
 * 排在后台的（比如一轮结束后记笔记）前面：有前台的在等，后台的不拿名额。只管插件之间的先后，不管对话本身的模型调用。
 */
final class PluginModelGate {

    /** 拿到的名额，用完关掉。 */
    interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    private static final class Slots {
        final ReentrantLock lock = new ReentrantLock(true);
        final Condition changed = lock.newCondition();
        final int limit;
        int used;
        int foregroundWaiting;

        Slots(int limit) {
            this.limit = Math.max(1, limit);
        }
    }

    private final Map<String, Slots> routes = new ConcurrentHashMap<>();
    private final ToIntFunction<String> limits;

    /** {@code limits} 给每条路线的名额数。 */
    PluginModelGate(ToIntFunction<String> limits) {
        this.limits = limits;
    }

    /** 等一个名额，最多等 {@code wait}；等不到返回 null。 */
    Permit acquire(String route, PluginServices.Priority priority, Duration wait) throws InterruptedException {
        Slots slots = routes.computeIfAbsent(route, name -> new Slots(limits.applyAsInt(name)));
        boolean foreground = priority == PluginServices.Priority.FOREGROUND;
        long left = wait.toNanos();
        slots.lock.lock();
        try {
            if (foreground) {
                slots.foregroundWaiting++;
            }
            try {
                while (slots.used >= slots.limit || (!foreground && slots.foregroundWaiting > 0)) {
                    if (left <= 0) {
                        return null;
                    }
                    left = slots.changed.awaitNanos(left);
                }
            } finally {
                if (foreground) {
                    slots.foregroundWaiting--;
                    slots.changed.signalAll();
                }
            }
            slots.used++;
        } finally {
            slots.lock.unlock();
        }
        boolean[] closed = {false};
        return () -> {
            slots.lock.lock();
            try {
                if (!closed[0]) {
                    closed[0] = true;
                    slots.used--;
                    slots.changed.signalAll();
                }
            } finally {
                slots.lock.unlock();
            }
        };
    }

    /** 测试用：这条路线现在用着几个名额。 */
    int used(String route) {
        Slots slots = routes.get(route);
        if (slots == null) {
            return 0;
        }
        slots.lock.lock();
        try {
            return slots.used;
        } finally {
            slots.lock.unlock();
        }
    }

    static final Duration FOREGROUND_WAIT = Duration.ofSeconds(30);
    static final Duration BACKGROUND_WAIT = Duration.ofMinutes(10);

    static Duration waitFor(PluginServices.Priority priority) {
        return priority == PluginServices.Priority.FOREGROUND ? FOREGROUND_WAIT : BACKGROUND_WAIT;
    }

    static long millis(Duration duration) {
        return TimeUnit.NANOSECONDS.toMillis(duration.toNanos());
    }
}
