package tantalum.shell.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个用户的记忆目录。写和删在本实例的锁内串行；文件先写临时文件再重命名，读到的要么是旧版要么是新版。
 * 索引 {@code MEMORY.md} 以条目文件为准，每次写删后重建。
 */
public final class MemoryStore {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryStore.class);
    private static final String SUFFIX = ".md";
    private static final String TEMP = ".tmp";

    /** 写入结果：成功时说明是新建还是覆盖，失败时给出能直接转给模型或用户的原因。 */
    public sealed interface WriteResult {
        record Created(MemoryEntry entry) implements WriteResult {}
        record Updated(MemoryEntry entry) implements WriteResult {}
        record Rejected(String reason) implements WriteResult {}
    }

    private final Path dir;
    private final MemoryLimits limits;
    private final Clock clock;

    public MemoryStore(Path dir, MemoryLimits limits, Clock clock) {
        this.dir = dir.toAbsolutePath().normalize();
        this.limits = limits;
        this.clock = clock;
    }

    public Path dir() {
        return dir;
    }

    public MemoryLimits limits() {
        return limits;
    }

    /** 索引内容，进 system 前的样子：坏行跳过并记日志，超限时截断并写明。没有记忆时为空串。 */
    public String indexText() {
        Path index = dir.resolve(MemoryFormat.INDEX_FILE);
        try {
            if (!Files.isRegularFile(index)) {
                return "";
            }
            StringBuilder text = new StringBuilder();
            int used = 0;
            boolean cut = false;
            for (String line : Files.readString(index).lines().toList()) {
                if (line.isBlank()) {
                    continue;
                }
                if (MemoryFormat.parseIndex(line).isEmpty()) {
                    LOG.warn("memory index of {}: skipping a malformed line", dir.getFileName());
                    continue;
                }
                int bytes = MemoryFormat.utf8Length(line) + 1;
                if (used + bytes > limits.maxIndexBytes()) {
                    cut = true;
                    break;
                }
                text.append(line).append('\n');
                used += bytes;
            }
            if (cut) {
                text.append("(The memory index was truncated at ").append(limits.maxIndexBytes()).append(" bytes; some entries are not listed.)\n");
            }
            return text.toString();
        } catch (IOException failure) {
            LOG.warn("cannot read memory index for {}: {}", dir.getFileName(), failure.toString());
            return "";
        }
    }

    /** 全部条目，按更新时间倒序；解析不了的文件跳过并记日志。 */
    public List<MemoryEntry> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<MemoryEntry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(SUFFIX) || fileName.equals(MemoryFormat.INDEX_FILE) || !Files.isRegularFile(file)) {
                    continue;
                }
                String name = fileName.substring(0, fileName.length() - SUFFIX.length());
                if (!MemoryFormat.isValidName(name)) {
                    continue;
                }
                parseFile(file).filter(entry -> entry.name().equals(name)).ifPresent(entries::add);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        entries.sort(Comparator.comparing(MemoryEntry::updatedAt).reversed().thenComparing(MemoryEntry::name));
        return entries;
    }

    public Optional<MemoryEntry> read(String name) {
        if (!MemoryFormat.isValidName(name)) {
            return Optional.empty();
        }
        Path file = fileOf(name);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return parseFile(file).filter(entry -> entry.name().equals(name));
    }

    /** 新建或同名覆盖。校验、凭据检查和上限都在这里，调用方只需转达 {@code Rejected.reason()}。 */
    public WriteResult write(String name, String type, String description, String content) {
        Optional<String> invalid = MemoryFormat.validate(name, type, description, content, limits.maxEntryBytes());
        if (invalid.isPresent()) {
            return new WriteResult.Rejected(invalid.get());
        }
        if (CredentialGuard.looksLikeCredential(description, content)) {
            return new WriteResult.Rejected(CredentialGuard.REASON);
        }
        MemoryEntry entry = new MemoryEntry(name, MemoryType.parse(type).orElseThrow(), description.trim(), content, clock.instant());
        synchronized (this) {
            List<MemoryEntry> existing = list();
            boolean replaces = existing.stream().anyMatch(other -> other.name().equals(name));
            if (!replaces && existing.size() >= limits.maxEntries()) {
                return new WriteResult.Rejected("memory is full (" + existing.size() + "/" + limits.maxEntries()
                        + " entries); delete one with memory_delete or merge into an existing entry with memory_write");
            }
            List<MemoryEntry> after = new ArrayList<>(existing.stream().filter(other -> !other.name().equals(name)).toList());
            after.add(entry);
            String index = MemoryFormat.formatIndex(after);
            int indexBytes = MemoryFormat.utf8Length(index);
            if (indexBytes > limits.maxIndexBytes()) {
                return new WriteResult.Rejected("memory index would be " + indexBytes + " bytes; the limit is "
                        + limits.maxIndexBytes() + ". Shorten descriptions or delete entries first");
            }
            try {
                ensureDirectory();
                writeAtomically(fileOf(name), MemoryFormat.formatEntry(entry));
                writeAtomically(dir.resolve(MemoryFormat.INDEX_FILE), index);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            return replaces ? new WriteResult.Updated(entry) : new WriteResult.Created(entry);
        }
    }

    /** 删除条目并重建索引；不存在返回 false。 */
    public boolean delete(String name) {
        if (!MemoryFormat.isValidName(name)) {
            return false;
        }
        synchronized (this) {
            Path file = fileOf(name);
            if (!Files.isRegularFile(file)) {
                return false;
            }
            try {
                Files.delete(file);
                writeAtomically(dir.resolve(MemoryFormat.INDEX_FILE), MemoryFormat.formatIndex(list()));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            return true;
        }
    }

    private Path fileOf(String name) {
        Path file = dir.resolve(name + SUFFIX).normalize();
        if (!file.getParent().equals(dir)) {
            throw new IllegalArgumentException("memory name escapes its directory");
        }
        return file;
    }

    private Optional<MemoryEntry> parseFile(Path file) {
        try {
            Optional<MemoryEntry> parsed = MemoryFormat.parseEntry(Files.readString(file));
            if (parsed.isEmpty()) {
                LOG.warn("skipping unparsable memory file {} for {}", file.getFileName(), dir.getFileName());
            }
            return parsed;
        } catch (IOException failure) {
            LOG.warn("skipping unreadable memory file {} for {}: {}", file.getFileName(), dir.getFileName(), failure.toString());
            return Optional.empty();
        }
    }

    private void ensureDirectory() throws IOException {
        if (Files.isDirectory(dir)) {
            return;
        }
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException notPosix) {
            // 非 POSIX 文件系统靠默认 ACL。
        }
    }

    private static void writeAtomically(Path target, String text) throws IOException {
        // 随机名加 CREATE_NEW：已存在的路径（包括别人放的符号链接）不会被跟随。
        Path temp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + TEMP);
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
