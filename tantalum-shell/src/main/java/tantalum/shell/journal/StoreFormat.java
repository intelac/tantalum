package tantalum.shell.journal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.local.fs.PrivateFiles;
import tantalum.shell.json.JsonText;
import tantalum.value.json.Json;

/**
 * 数据目录的 {@code format.json}。{@code storage} 是文件格式，比程序新就拒绝启动，不改动数据。
 * 以前还有 {@code semantics}（内核语义）：重放旧会话可能和当时不同时记警告。恢复改成只读结果行之后不再重放，
 * 这个字段不再写，读到也忽略。
 */
public record StoreFormat(int storage) {

    /** 3：会话文件一步一行。更早的会话文件不再读，见 {@link #check}。 */
    public static final int STORAGE = 3;

    private static final Logger LOG = LoggerFactory.getLogger(StoreFormat.class);
    private static final String FILE = "format.json";
    private static final String LEGACY_JOURNAL = "journal.db";

    /** 校验数据目录并写入当前版本。拒绝时抛 {@link IllegalStateException}，消息说明原因。 */
    public static StoreFormat check(Path dataDir) throws IOException {
        Path file = dataDir.resolve(FILE);
        StoreFormat current = new StoreFormat(STORAGE);
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
        if (found.storage < STORAGE) {
            Path sessions = dataDir.resolve("sessions");
            if (hasSessionFiles(sessions)) {
                throw new IllegalStateException(dataDir + " holds session files of storage version " + found.storage
                        + ", which this version no longer reads. Move " + sessions + " and " + dataDir.resolve("index.db")
                        + " out of the data directory and start again; users, plugin switches and settings stay.");
            }
            LOG.info("upgrading {} from storage version {} to {}: it holds no session files", dataDir, found.storage, STORAGE);
            write(dataDir, new StoreFormat(STORAGE));
            return current;
        }
        return current;
    }

    private static boolean hasSessionFiles(Path sessions) throws IOException {
        if (!Files.isDirectory(sessions)) {
            return false;
        }
        try (var files = Files.walk(sessions)) {
            return files.anyMatch(file -> file.getFileName().toString().endsWith(".jsonl"));
        }
    }

    private static StoreFormat read(Path file) throws IOException {
        Json parsed = JsonText.parseOrNull(Files.readString(file, StandardCharsets.UTF_8));
        if (parsed instanceof Json.Obj format
                && format.fields().get("storage") instanceof Json.Num storage) {
            return new StoreFormat(storage.value().intValue());
        }
        throw new IllegalStateException(file + " is not a valid format file; refusing to start");
    }

    /** 写临时文件再重命名，崩溃时不会留下半个文件。 */
    private static void write(Path dataDir, StoreFormat format) throws IOException {
        Files.createDirectories(dataDir);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("storage", format.storage);
        Path temp = PrivateFiles.createTempFile(dataDir, FILE, ".tmp");
        try {
            Files.writeString(temp, JsonText.toText(JsonText.fromPlain(fields)) + "\n", StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            PrivateFiles.replace(temp, dataDir.resolve(FILE));
        } finally {
            Files.deleteIfExists(temp);
        }
        PrivateFiles.syncDirectory(dataDir);
    }
}
