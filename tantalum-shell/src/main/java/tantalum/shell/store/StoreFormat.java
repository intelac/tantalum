package tantalum.shell.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.journal.JsonText;
import tantalum.value.json.Json;

/**
 * 数据目录的 {@code format.json}。{@code storage} 是文件格式，比程序新就拒绝启动，不改动数据。
 * {@code semantics} 是内核语义，变化只记警告：旧程序已经不在了，无法精确重放，journal 原文不受影响。
 */
public record StoreFormat(int storage, int semantics) {

    public static final int STORAGE = 1;
    public static final int SEMANTICS = 1;

    private static final Logger LOG = LoggerFactory.getLogger(StoreFormat.class);
    private static final String FILE = "format.json";
    private static final String LEGACY_JOURNAL = "journal.db";

    /** 校验数据目录并写入当前版本。拒绝时抛 {@link IllegalStateException}，消息说明原因。 */
    public static StoreFormat check(Path dataDir) throws IOException {
        Path file = dataDir.resolve(FILE);
        StoreFormat current = new StoreFormat(STORAGE, SEMANTICS);
        if (!Files.exists(file)) {
            if (Files.exists(dataDir.resolve(LEGACY_JOURNAL))) {
                throw new IllegalStateException(dataDir + " holds an old " + LEGACY_JOURNAL
                        + " and no " + FILE + "; this version cannot read it. Point tantalum at a new data directory.");
            }
            write(dataDir, current);
            return current;
        }
        StoreFormat found = read(file);
        if (found.storage > STORAGE) {
            throw new IllegalStateException(file + " has storage version " + found.storage
                    + " but this program supports up to " + STORAGE + ". Upgrade tantalum.");
        }
        if (found.semantics != SEMANTICS) {
            LOG.warn("kernel semantics changed from {} to {}: replaying old sessions may not match the original prompts byte for byte",
                    found.semantics, SEMANTICS);
            write(dataDir, new StoreFormat(found.storage, SEMANTICS));
        }
        return current;
    }

    private static StoreFormat read(Path file) throws IOException {
        Json parsed = JsonText.parseOrNull(Files.readString(file, StandardCharsets.UTF_8));
        if (parsed instanceof Json.Obj format
                && format.fields().get("storage") instanceof Json.Num storage
                && format.fields().get("semantics") instanceof Json.Num semantics) {
            return new StoreFormat(storage.value().intValue(), semantics.value().intValue());
        }
        throw new IllegalStateException(file + " is not a valid format file; refusing to start");
    }

    /** 写临时文件再重命名，崩溃时不会留下半个文件。 */
    private static void write(Path dataDir, StoreFormat format) throws IOException {
        Files.createDirectories(dataDir);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("storage", format.storage);
        fields.put("semantics", format.semantics);
        Path temp = Files.createTempFile(dataDir, FILE, ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temp, JsonText.toText(JsonText.fromPlain(fields)) + "\n", StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temp, dataDir.resolve(FILE), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        try (FileChannel channel = FileChannel.open(dataDir, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
