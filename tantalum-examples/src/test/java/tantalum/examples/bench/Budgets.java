package tantalum.examples.bench;

import java.util.ArrayList;
import java.util.List;

/**
 * ops-hardening 的性能预算（Yi 于 2026-11-02 确认，见设计文档“性能预算”）。数字只能在这里改。
 * {@code bench.tighten} 只能把预算收紧（0 到 1 之间），用来确认超预算时 bench 会失败；不能放宽。
 */
final class Budgets {

    /** 预算针对的规模。 */
    static final int AT_EVENTS = 500_000;
    /** 需求 2.2：无未结束运行时启动。 */
    static final long STARTUP_MS = 5_000;
    /** 需求 2.3：启动后留存堆，且比 1 万事件时多不超过 {@link #RETAINED_GROWTH_MB}。 */
    static final long RETAINED_HEAP_MB = 100;
    static final long RETAINED_GROWTH_MB = 20;
    /** 需求 5.3：安静用户的 {@code /turns}、{@code /trace}。 */
    static final long QUIET_QUERY_MS = 200;
    /** 需求 8.2：打开 500 条消息的会话，常驻 / 冷载入。 */
    static final long OPEN_RESIDENT_MS = 100;
    static final long OPEN_COLD_MS = 1_000;
    /** 非功能：100 会话、每会话每秒 1 事件的落盘 P99。 */
    static final long WRITE_P99_MS = 50;

    private final double tighten;
    private final List<String> failures = new ArrayList<>();

    Budgets(double tighten) {
        if (!(tighten > 0 && tighten <= 1)) {
            throw new IllegalArgumentException("bench.tighten must be in (0, 1]; budgets cannot be relaxed: " + tighten);
        }
        this.tighten = tighten;
    }

    static Budgets fromSystemProperty() {
        return new Budgets(Double.parseDouble(System.getProperty("bench.tighten", "1")));
    }

    void check(String name, long measured, long budget) {
        long limit = Math.max(1, Math.round(budget * tighten));
        boolean ok = measured <= limit;
        System.out.printf("BUDGET %-24s %8d <= %8d  %s%n", name, measured, limit, ok ? "ok" : "OVER");
        if (!ok) {
            failures.add(name + " " + measured + " > " + limit);
        }
    }

    /** 有超预算的项时以非零状态退出。 */
    void exitIfOver() {
        if (failures.isEmpty()) {
            System.out.println("all budgets met");
            return;
        }
        System.out.println("over budget: " + failures);
        System.exit(1);
    }
}
