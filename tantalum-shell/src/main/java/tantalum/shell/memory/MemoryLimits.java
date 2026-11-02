package tantalum.shell.memory;

/** 每用户长期记忆的上限：条目数、索引总字节、单条正文字节。超限时写入被拒，不自动整理。 */
public record MemoryLimits(int maxEntries, int maxIndexBytes, int maxEntryBytes) {

    public MemoryLimits {
        if (maxEntries <= 0 || maxIndexBytes <= 0 || maxEntryBytes <= 0) {
            throw new IllegalArgumentException("memory limits must be positive");
        }
    }

    public static MemoryLimits defaults() {
        return new MemoryLimits(200, 16 * 1024, 8 * 1024);
    }
}
