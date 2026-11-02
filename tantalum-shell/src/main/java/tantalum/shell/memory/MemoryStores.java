package tantalum.shell.memory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import tantalum.shell.tool.Workspaces;
import tantalum.value.id.Principal;

/**
 * 按用户给出 {@link MemoryStore}。每人模式下记忆放在该用户的工作目录里（他自己的工具能读到）；
 * 共用模式下放到数据目录，因为共用的工作目录会被所有人的 grep 搜到。
 */
public final class MemoryStores {

    private final Workspaces workspaces;
    private final Path sharedRoot;
    private final MemoryLimits limits;
    private final Clock clock;
    private final Map<Principal, MemoryStore> stores = new ConcurrentHashMap<>();

    public MemoryStores(Workspaces workspaces, Path dataDir, MemoryLimits limits, Clock clock) {
        this.workspaces = workspaces;
        this.sharedRoot = dataDir.resolve("memory");
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
        if (!Workspaces.isSafeName(principal.value())) {
            throw new IllegalArgumentException("user name cannot be used as a directory: " + principal.value());
        }
        MemoryStore opened = new MemoryStore(directoryFor(principal), limits, clock);
        MemoryStore raced = stores.putIfAbsent(principal, opened);
        return raced == null ? opened : raced;
    }

    private Path directoryFor(Principal principal) {
        if (workspaces.isShared()) {
            return sharedRoot.resolve(principal.value());
        }
        return workspaces.of(principal).root().resolve("memory");
    }
}
