package tantalum.shell.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import tantalum.shell.boot.TantalumProperties.Loop;
import tantalum.value.id.Principal;

/** 外部事件的入口限额。超限直接拒绝，不排队，这样 Input Ring 总有内部结果的位置。 */
public final class ExternalGate {

    public enum Verdict {
        ADMITTED,
        OVER_LIMIT,
        HALTED
    }

    private final Loop limits;
    private final IntSupplier activeRuns;
    private final BooleanSupplier halted;
    private final Map<Principal, AtomicInteger> perUser = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger newRuns = new AtomicInteger();

    public ExternalGate(Loop limits, IntSupplier activeRuns, BooleanSupplier halted) {
        this.limits = limits;
        this.activeRuns = activeRuns;
        this.halted = halted;
    }

    /** {@code needsNewRun} 为 true 时还要检查活跃运行数。 */
    public synchronized Verdict admit(Principal principal, boolean needsNewRun) {
        if (halted.getAsBoolean()) {
            return Verdict.HALTED;
        }
        AtomicInteger user = perUser.computeIfAbsent(principal, key -> new AtomicInteger());
        if (user.get() >= limits.maxUserInFlight() || total.get() >= limits.maxExternalInFlight()) {
            return Verdict.OVER_LIMIT;
        }
        if (needsNewRun && activeRuns.getAsInt() + newRuns.get() >= limits.maxActiveRuns()) {
            return Verdict.OVER_LIMIT;
        }
        user.incrementAndGet();
        total.incrementAndGet();
        if (needsNewRun) {
            newRuns.incrementAndGet();
        }
        return Verdict.ADMITTED;
    }

    /** 事件被内核处理后调用；它产生的运行此后计入 {@code activeRuns}。 */
    public synchronized void release(Principal principal) {
        perUser.computeIfAbsent(principal, key -> new AtomicInteger()).decrementAndGet();
        total.decrementAndGet();
    }

    public synchronized void releaseNewRun() {
        newRuns.decrementAndGet();
    }
}
