package tantalum.shell.loop;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import tantalum.value.id.Principal;

/** 外部事件的入口限额。超限直接拒绝，不排队，这样 Input Ring 总有内部结果的位置。所有方法都在同一把锁下。 */
public final class ExternalGate {

    /** 每个用户和全部外部事件的在途上限，以及活跃运行上限。 */
    public record Limits(int maxActiveRuns, int maxUserInFlight, int maxExternalInFlight) {}

    public enum Verdict {
        ADMITTED,
        OVER_LIMIT,
        HALTED
    }

    private final Limits limits;
    private final IntSupplier activeRuns;
    private final BooleanSupplier halted;
    /** 只保存在途数大于 0 的用户，归零即删，不随见过的用户数增长。 */
    private final Map<Principal, Integer> perUser = new HashMap<>();
    private int total;
    private int newRuns;

    public ExternalGate(Limits limits, IntSupplier activeRuns, BooleanSupplier halted) {
        this.limits = limits;
        this.activeRuns = activeRuns;
        this.halted = halted;
    }

    /** {@code needsNewRun} 为 true 时还要检查活跃运行数。 */
    /** 进程已停止处理事件：重复提交也不能回“已接受”，原提交可能没写进去。 */
    public boolean halted() {
        return halted.getAsBoolean();
    }

    public synchronized Verdict admit(Principal principal, boolean needsNewRun) {
        if (halted.getAsBoolean()) {
            return Verdict.HALTED;
        }
        int user = perUser.getOrDefault(principal, 0);
        if (user >= limits.maxUserInFlight() || total >= limits.maxExternalInFlight()) {
            return Verdict.OVER_LIMIT;
        }
        if (needsNewRun && activeRuns.getAsInt() + newRuns >= limits.maxActiveRuns()) {
            return Verdict.OVER_LIMIT;
        }
        perUser.put(principal, user + 1);
        total++;
        if (needsNewRun) {
            newRuns++;
        }
        return Verdict.ADMITTED;
    }

    /** 事件被内核处理后调用；它产生的运行此后计入 {@code activeRuns}。多余的释放不会让计数变负。 */
    public synchronized void release(Principal principal) {
        Integer user = perUser.get(principal);
        if (user == null) {
            return;
        }
        if (user <= 1) {
            perUser.remove(principal);
        } else {
            perUser.put(principal, user - 1);
        }
        total = Math.max(0, total - 1);
    }

    public synchronized void releaseNewRun() {
        newRuns = Math.max(0, newRuns - 1);
    }

    synchronized int trackedUsers() {
        return perUser.size();
    }
}
