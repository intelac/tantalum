package tantalum.shell.memory;

import java.time.Instant;
import java.util.Objects;

/** 一条记忆。{@code name} 是文件名也是标识；{@code updatedAt} 由存储在写入时填。 */
public record MemoryEntry(String name, MemoryType type, String description, String content, Instant updatedAt) {

    public MemoryEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public MemoryEntry withUpdatedAt(Instant at) {
        return new MemoryEntry(name, type, description, content, at);
    }
}
