package tantalum.memory.ledger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.local.fs.PrivateFiles;

/**
 * 一个人的记忆账（memory-final-plan 第 3、4 节）：一个只追加的文件，每行是一条条目的新版本、一个墓碑或一个记号。
 * 打开时从头读一遍，得到每条的最新版本。写入在本实例的锁内排队，每次落盘（fsync）后才算数；写之前比对版本，不一致就拒绝并给出当前内容。
 * 最后一行没写完（崩溃时）就截掉；中间坏的行跳过并记日志。
 */
public final class Ledger {

    public static final String FILE = "ledger.jsonl";
    private static final Logger LOG = LoggerFactory.getLogger(Ledger.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 写入的结果。 */
    public sealed interface Result {
        record Written(MemoryItem item) implements Result {}
        /** 版本对不上：别人先改了，{@code current} 是现在的样子（没有为 null）。 */
        record Conflict(MemoryItem current) implements Result {}
    }

    private final Path file;
    private final Clock clock;
    private final Map<String, MemoryItem> latest = new LinkedHashMap<>();
    private final Map<String, LedgerCodec.Tombstone> tombstones = new LinkedHashMap<>();
    private final List<String> markers = new ArrayList<>();

    private Ledger(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /** 打开（不存在就是空的，第一次写时建）。 */
    public static Ledger open(Path dir, Clock clock) {
        Ledger ledger = new Ledger(dir.toAbsolutePath().normalize().resolve(FILE), clock);
        ledger.load();
        return ledger;
    }

    public Path file() {
        return file;
    }

    /** 新编号：时间加随机数，按时间大致有序。 */
    public String newId() {
        return newId(clock);
    }

    public static String newId(Clock clock) {
        byte[] random = new byte[5];
        RANDOM.nextBytes(random);
        return "m" + Long.toString(clock.millis(), 36) + HexFormat.of().formatHex(random);
    }

    public Clock clock() {
        return clock;
    }

    /**
     * 写一条：新的条目 {@code expectedVersion} 给 0；改已有的给它现在的版本号。版本号和时间由账来定。
     * 墓碑过的条目不能再写（返回 Conflict，当前内容为 null）。
     */
    public synchronized Result put(MemoryItem item, int expectedVersion) {
        MemoryItem current = latest.get(item.id());
        int now = current == null ? 0 : current.version();
        if (now != expectedVersion || tombstones.containsKey(item.id())) {
            return new Result.Conflict(tombstones.containsKey(item.id()) ? null : current);
        }
        MemoryItem next = item.nextVersion(now + 1, clock.instant());
        if (current != null) {
            next = new MemoryItem(next.id(), next.version(), next.state(), next.kind(), next.page(), next.scope(), next.source(),
                    next.quote(), next.provenance(), next.actor(), next.validFrom(), next.supersededBy(), next.lastConfirmed(),
                    next.confidence(), next.derivedFrom(), next.status(), next.pendingConfirm(), next.text(), next.attrs(), next.owner(),
                    next.visibleTo(), next.certifiedHash(), next.consent(), current.created(), next.updated());
        }
        append(new LedgerCodec.Put(next));
        latest.put(next.id(), next);
        return new Result.Written(next);
    }

    /** “不再使用”：已经墓碑过或者没有这条时返回 false。 */
    public synchronized boolean tombstone(String id, MemoryItem.Actor actor, String reason) {
        if (!latest.containsKey(id) || tombstones.containsKey(id)) {
            return false;
        }
        LedgerCodec.Tombstone line = new LedgerCodec.Tombstone(id, clock.instant(), actor, reason);
        append(line);
        tombstones.put(id, line);
        return true;
    }

    public synchronized boolean tombstoned(String id) {
        return tombstones.containsKey(id);
    }

    /** 留墓碑的时刻；没有墓碑为空。 */
    public synchronized Optional<java.time.Instant> tombstonedAt(String id) {
        return Optional.ofNullable(tombstones.get(id)).map(LedgerCodec.Tombstone::at);
    }

    public synchronized Optional<MemoryItem> get(String id) {
        return Optional.ofNullable(latest.get(id));
    }

    /** 没被墓碑的条目（任何状态），按第一次写入的顺序。 */
    public synchronized List<MemoryItem> items(Predicate<MemoryItem> filter) {
        List<MemoryItem> result = new ArrayList<>();
        for (MemoryItem item : latest.values()) {
            if (!tombstones.containsKey(item.id()) && filter.test(item)) {
                result.add(item);
            }
        }
        return result;
    }

    /** 包括墓碑过的（查“记过没有”这类用）。 */
    public synchronized List<MemoryItem> everything(Predicate<MemoryItem> filter) {
        return latest.values().stream().filter(filter).toList();
    }

    public synchronized boolean hasMarker(String name) {
        return markers.contains(name);
    }

    public synchronized List<String> markers() {
        return Collections.unmodifiableList(new ArrayList<>(markers));
    }

    /** 写一个记号（迁移完成这类），已有就不写。 */
    public synchronized void mark(String name) {
        if (!markers.contains(name)) {
            append(new LedgerCodec.Marker(name, clock.instant()));
            markers.add(name);
        }
    }

    /** 把整份内容一次写成新的账（迁移用）：先写临时文件再换过去，中途崩溃不留半份。只能在账还不存在时用。 */
    static void writeFresh(Path dir, List<LedgerCodec.Line> lines) {
        Path target = dir.toAbsolutePath().normalize().resolve(FILE);
        if (Files.exists(target)) {
            throw new IllegalStateException("the ledger already exists: " + target);
        }
        try {
            ensureDirectory(target.getParent());
            Path temp = target.resolveSibling(FILE + ".import.tmp");
            Files.deleteIfExists(temp);
            StringBuilder text = new StringBuilder();
            lines.forEach(line -> text.append(LedgerCodec.encode(line)).append('\n'));
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
            PrivateFiles.replace(temp, target);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        int lastNewline = text.lastIndexOf('\n');
        if (lastNewline < text.length() - 1) {
            // 最后一行没写完：截掉，之后照常追加
            LOG.warn("memory ledger {} ends with a partial line; cutting it off", file);
            truncate(lastNewline < 0 ? 0 : text.substring(0, lastNewline + 1).getBytes(StandardCharsets.UTF_8).length);
            text = lastNewline < 0 ? "" : text.substring(0, lastNewline + 1);
        }
        int number = 0;
        for (String raw : text.split("\n")) {
            number++;
            if (raw.isBlank()) {
                continue;
            }
            try {
                apply(LedgerCodec.decode(raw));
            } catch (RuntimeException bad) {
                LOG.warn("memory ledger {}: skipping unreadable line {}: {}", file, number, bad.toString());
            }
        }
    }

    private void apply(LedgerCodec.Line line) {
        switch (line) {
            case LedgerCodec.Put put -> latest.put(put.item().id(), put.item());
            case LedgerCodec.Tombstone tombstone -> tombstones.put(tombstone.id(), tombstone);
            case LedgerCodec.Marker marker -> {
                if (!markers.contains(marker.name())) {
                    markers.add(marker.name());
                }
            }
        }
    }

    private void append(LedgerCodec.Line line) {
        byte[] bytes = (LedgerCodec.encode(line) + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            ensureDirectory(file.getParent());
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private void truncate(long size) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(size);
            channel.force(true);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void ensureDirectory(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            return;
        }
        Files.createDirectories(dir);
        PrivateFiles.restrictDirectory(dir);
    }

    /** 测试用：现在的时间。 */
    Instant now() {
        return clock.instant();
    }
}
