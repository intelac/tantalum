package tantalum.memory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import tantalum.value.id.Principal;

/**
 * 按用户给出 {@link MemoryStore}。目录由平台给（插件数据目录，memory-plugin 需求 1.3，目录名 {@code memory}）：每人模式在这个人的
 * 工作区里（他自己的工具能读到），共用模式在数据目录下，因为共用的工作目录会被所有人的 grep 搜到。
 */
public final class MemoryStores {

    /** 插件数据目录的名字；和以前的记忆目录同名，已有的记忆和笔记原样可用。 */
    public static final String DIR = "memory";

    private final Function<Principal, Path> dirs;
    private final MemoryLimits limits;
    private final Clock clock;
    private final Map<Principal, MemoryStore> stores = new ConcurrentHashMap<>();
    private final Map<Principal, DailyNotes> notes = new ConcurrentHashMap<>();

    /** {@code dirs} 给出这个用户的记忆目录，名字不安全时抛 {@link IllegalArgumentException}。 */
    public MemoryStores(Function<Principal, Path> dirs, MemoryLimits limits, Clock clock) {
        this.dirs = dirs;
        this.limits = limits;
        this.clock = clock;
    }

    /** 同一用户总是同一个实例，写删的锁才有效。名字不安全时抛 {@link IllegalArgumentException}。 */
    public MemoryStore of(Principal principal) {
        Objects.requireNonNull(principal, "principal");
        MemoryStore existing = stores.get(principal);
        if (existing != null) {
            return existing;
        }
        if (!Principal.isUsable(principal.value())) {
            throw new IllegalArgumentException("user name cannot be used as a directory: " + principal.value());
        }
        MemoryStore opened = new MemoryStore(dirs.apply(principal), limits, clock);
        MemoryStore raced = stores.putIfAbsent(principal, opened);
        return raced == null ? opened : raced;
    }

    /** 这个人每天的笔记（memory-capture）：和记忆在同一个目录下；同一用户总是同一个实例，锁才有效。 */
    public DailyNotes notes(Principal principal) {
        return notes.computeIfAbsent(principal, user -> new DailyNotes(of(user).dir()));
    }
}
