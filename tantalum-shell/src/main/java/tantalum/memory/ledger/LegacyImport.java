package tantalum.memory.ledger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.memory.MemoryEntry;
import tantalum.memory.MemoryFormat;

/**
 * 把旧格式的记忆目录（条目 {@code <名字>.md}、每天的笔记 {@code daily/…}、删掉的笔记 {@code .deleted}）一次导进记忆账
 * （memory-final-plan 第 6 节“现有数据”）。只在账还不存在时做；先写临时文件再换过去，中途崩溃不留半份，重跑结果一样。
 * 旧文件原样留着当备份。
 */
public final class LegacyImport {

    public static final String MARKER = "imported-v1";
    private static final Logger LOG = LoggerFactory.getLogger(LegacyImport.class);

    private LegacyImport() {}

    /** 账不存在、旧目录里有东西时导入；返回导入了几条。 */
    public static int run(Path legacyDir, Path ledgerDir, Supplier<String> ids, Instant now) {
        if (Files.exists(ledgerDir.resolve(Ledger.FILE)) || legacyDir == null || !Files.isDirectory(legacyDir)) {
            return 0;
        }
        List<LedgerCodec.Line> lines = new ArrayList<>();
        for (MemoryEntry entry : entries(legacyDir)) {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("description", entry.description());
            attrs.put("type", entry.type().pageType());
            MemoryItem page = base(ids.get(), MemoryItem.Kind.PAGE, "", entry.updatedAt()).withPage(entry.name()).withAttrs(attrs);
            MemoryItem claim = base(ids.get(), MemoryItem.Kind.CLAIM, entry.content(), entry.updatedAt()).withPage(entry.name());
            lines.add(new LedgerCodec.Put(page));
            lines.add(new LedgerCodec.Put(claim));
        }
        for (LegacyNotes.File note : LegacyNotes.files(legacyDir)) {
            for (LegacyNotes.Section section : note.parsed().sections()) {
                lines.add(new LedgerCodec.Put(card(ids.get(), note.day(), note.session(), note.parsed().title(), section, note.modified())));
            }
        }
        for (String[] gone : LegacyNotes.tombstoned(legacyDir)) {
            String id = ids.get();
            lines.add(new LedgerCodec.Put(card(id, gone[0], gone[1], "", new LegacyNotes.Section(gone[2], "", ""), now)));
            lines.add(new LedgerCodec.Tombstone(id, now, MemoryItem.Actor.MIGRATION, "note was deleted before the import"));
        }
        if (lines.isEmpty()) {
            return 0;
        }
        lines.add(new LedgerCodec.Marker(MARKER, now));
        Ledger.writeFresh(ledgerDir, lines);
        int count = (int) lines.stream().filter(LedgerCodec.Put.class::isInstance).count();
        LOG.info("imported {} memory items from {}", count, legacyDir);
        return count;
    }

    private static MemoryItem base(String id, MemoryItem.Kind kind, String text, Instant at) {
        return MemoryItem.create(id, kind, MemoryItem.Source.LEGACY, MemoryItem.Actor.MIGRATION, text, at);
    }

    private static MemoryItem card(String id, String day, String session, String title, LegacyNotes.Section section, Instant at) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("day", day);
        attrs.put("session", session);
        attrs.put("title", title == null ? "" : title);
        if (section.run() != null) {
            attrs.put("run", section.run());
        }
        attrs.put("heading", section.heading());
        return base(id, MemoryItem.Kind.CARD, section.body(), at).withAttrs(attrs)
                .withProvenance(new MemoryItem.Provenance(session, section.run(), null, null));
    }

    private static List<MemoryEntry> entries(Path dir) {
        List<MemoryEntry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".md") || name.equals(MemoryFormat.INDEX_FILE) || !Files.isRegularFile(file)) {
                    continue;
                }
                Optional<MemoryEntry> entry = MemoryFormat.parseEntry(Files.readString(file, StandardCharsets.UTF_8));
                if (entry.isPresent() && entry.get().name().equals(name.substring(0, name.length() - 3))) {
                    entries.add(entry.get());
                } else {
                    LOG.warn("not importing unparsable memory file {}", file.getFileName());
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return entries;
    }
}
