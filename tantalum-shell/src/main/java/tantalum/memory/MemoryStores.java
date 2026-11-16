package tantalum.memory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.LegacyImport;
import tantalum.value.id.Principal;

/**
 * 按用户给出记忆账和它上面的两样视图（{@link MemoryStore}、{@link DailyNotes}）。账在插件的私有状态目录（数据目录下，
 * 不在工作区里）；第一次打开时，旧格式的记忆目录如果有东西，先导进来（{@link LegacyImport}）。同一用户总是同一份实例，锁才有效。
 */
public final class MemoryStores {

    /** 旧格式记忆目录的名字（插件数据目录 {@code dataDir} 下）；只用来导入。 */
    public static final String DIR = "memory";

    private final Function<Principal, Path> stateDirs;
    private final Function<Principal, Path> legacyDirs;
    private final MemoryLimits limits;
    private final Clock clock;
    private final Map<Principal, Ledger> ledgers = new ConcurrentHashMap<>();
    private final Map<Principal, MemoryStore> stores = new ConcurrentHashMap<>();
    private final Map<Principal, DailyNotes> notes = new ConcurrentHashMap<>();
    private final Map<Principal, WorkingMemory> working = new ConcurrentHashMap<>();
    private final SearchBudget budget = new SearchBudget();

    /** {@code stateDirs} 给出账的目录，{@code legacyDirs} 给出旧格式目录（没有可以返回 null）；名字不安全时抛 {@link IllegalArgumentException}。 */
    public MemoryStores(Function<Principal, Path> stateDirs, Function<Principal, Path> legacyDirs, MemoryLimits limits, Clock clock) {
        this.stateDirs = stateDirs;
        this.legacyDirs = legacyDirs;
        this.limits = limits;
        this.clock = clock;
    }

    /** 账和旧目录是同一个目录时（测试）。 */
    public MemoryStores(Function<Principal, Path> dirs, MemoryLimits limits, Clock clock) {
        this(dirs, dirs, limits, clock);
    }

    /** 这个人的记忆账。 */
    public Ledger ledger(Principal principal) {
        Objects.requireNonNull(principal, "principal");
        if (!Principal.isUsable(principal.value())) {
            throw new IllegalArgumentException("user name cannot be used as a directory: " + principal.value());
        }
        return ledgers.computeIfAbsent(principal, user -> {
            Path legacy = legacyDirs.apply(user);
            Path dir = stateDirs.apply(user).toAbsolutePath().normalize();
            if (legacy != null && legacy.toAbsolutePath().normalize().equals(dir)) {
                // 账和旧格式放在同一个目录时（测试），账放进子目录，免得和旧文件混在一起
                dir = dir.resolve("ledger");
            }
            LegacyImport.run(legacy, dir, () -> Ledger.newId(clock), clock.instant());
            return Ledger.open(dir, clock);
        });
    }

    /** 同一用户总是同一个实例，写删的锁才有效。名字不安全时抛 {@link IllegalArgumentException}。 */
    public MemoryStore of(Principal principal) {
        return stores.computeIfAbsent(principal, user -> new MemoryStore(ledger(user), limits));
    }

    /** 这个人每天的笔记（memory-capture）：和记忆在同一本账上。 */
    public DailyNotes notes(Principal principal) {
        return notes.computeIfAbsent(principal, user -> new DailyNotes(ledger(user)));
    }

    /** 这个人的工作记忆（memory-final-plan 第 5 节）：和记忆在同一本账上。 */
    /** 一轮里找记忆的次数，三个找记忆的工具共用。 */
    public SearchBudget budget() {
        return budget;
    }

    public WorkingMemory working(Principal principal) {
        return working.computeIfAbsent(principal, user -> new WorkingMemory(ledger(user)));
    }
}
