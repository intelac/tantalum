package tantalum.shell.memory;

import tantalum.shell.boot.TantalumProperties;

/** 每用户记忆上限。超限时写入被拒，不自动整理。 */
public record MemoryLimits(int maxEntries, int maxIndexBytes, int maxEntryBytes) {

    public MemoryLimits {
        if (maxEntries <= 0 || maxIndexBytes <= 0 || maxEntryBytes <= 0) {
            throw new IllegalArgumentException("memory limits must be positive");
        }
    }

    public static MemoryLimits of(TantalumProperties.Memory memory) {
        return new MemoryLimits(memory.maxEntries(), memory.maxIndexBytes(), memory.maxEntryBytes());
    }

    public static MemoryLimits defaults() {
        return of(TantalumProperties.Memory.defaults());
    }
}
