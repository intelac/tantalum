package tantalum.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.LegacyNotes;
import tantalum.memory.ledger.MemoryItem;

/**
 * 每天的笔记（memory-capture）：记忆账上的当天卡（{@link MemoryItem.Kind#CARD}），一轮一张，一个会话一天一篇。
 * 删掉的卡留墓碑，所以“这一轮记过没有”照样能答，重放和补抓不会把它们写回来。全文的样子和旧格式逐字相同（控制台照旧编辑）。
 */
public final class DailyNotes {

    static final int PREVIEW_CHARS = 160;
    static final String DAY = "day";
    static final String SESSION = "session";
    static final String TITLE = "title";
    static final String RUN = "run";
    static final String HEADING = "heading";

    /** 一段：哪一轮、标题行、正文。 */
    public record Section(String run, String heading, String body) {}

    /** 一篇笔记的全文和分段。 */
    public record Note(String day, String session, String title, String text, List<Section> sections, Instant updated) {}

    /** 列表里的一篇。 */
    public record Listed(String session, String title, int sections, String preview, Instant updated) {}

    /** 一天的笔记，新的在前。 */
    public record Day(String day, List<Listed> notes) {}

    private final Ledger ledger;

    public DailyNotes(Ledger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    /** 测试和单独使用：在 {@code dir} 打开一本账。 */
    public DailyNotes(java.nio.file.Path dir) {
        this(Ledger.open(dir, Clock.systemUTC()));
    }

    /** 这些笔记所在的记忆账。 */
    public Ledger ledger() {
        return ledger;
    }

    public static boolean validDay(String day) {
        return LegacyNotes.validDay(day);
    }

    public static boolean validSession(String session) {
        return LegacyNotes.validSession(session);
    }

    /** 这一轮记过没有（卡还在，或者删掉了留着墓碑）。 */
    public boolean noted(String day, String session, String run) {
        check(day, session);
        return !ledger.everything(item -> isCard(item, day, session) && run.equals(item.attr(RUN))).isEmpty();
    }

    /** 在这一天这个会话的笔记后面加一段。这一轮记过就不写，返回 false。 */
    public synchronized boolean append(String day, String session, String title, String run, String heading, String body) {
        check(day, session);
        if (!LegacyNotes.MARKER.matcher("<!-- run:" + run + " -->").matches()) {
            throw new IllegalArgumentException("invalid run id: " + run);
        }
        if (noted(day, session, run)) {
            return false;
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put(DAY, day);
        attrs.put(SESSION, session);
        attrs.put(TITLE, cleanTitle(title));
        attrs.put(RUN, run);
        attrs.put(HEADING, heading == null ? "" : heading);
        MemoryItem card = MemoryItem.create(ledger.newId(), MemoryItem.Kind.CARD, MemoryItem.Source.ASSISTANT_INFERRED,
                MemoryItem.Actor.AGENT, body.strip(), ledger.clock().instant()).withAttrs(attrs)
                .withProvenance(new MemoryItem.Provenance(session, run, null, null));
        return ledger.put(card, 0) instanceof Ledger.Result.Written;
    }

    /** 最近 {@code days} 天有笔记的日子，新的在前；每天的笔记按更新时间新的在前。 */
    public List<Day> list(int days) {
        Map<String, Map<String, List<MemoryItem>>> byDay = new TreeMap<>(Comparator.reverseOrder());
        for (MemoryItem card : ledger.items(item -> item.kind() == MemoryItem.Kind.CARD && item.state() != MemoryItem.State.WITHDRAWN)) {
            byDay.computeIfAbsent(card.attr(DAY), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(card.attr(SESSION), ignored -> new ArrayList<>()).add(card);
        }
        List<Day> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<MemoryItem>>> day : byDay.entrySet()) {
            if (result.size() >= days) {
                break;
            }
            List<Listed> notes = new ArrayList<>();
            day.getValue().forEach((session, cards) -> {
                MemoryItem last = cards.getLast();
                Instant updated = cards.stream().map(MemoryItem::updated).max(Comparator.naturalOrder()).orElse(last.updated());
                notes.add(new Listed(session, title(cards), cards.size(), cut(last.text()), updated));
            });
            notes.sort(Comparator.comparing(Listed::updated).reversed().thenComparing(Listed::session));
            result.add(new Day(day.getKey(), notes));
        }
        return result;
    }

    public Optional<Note> read(String day, String session) {
        check(day, session);
        List<MemoryItem> cards = cards(day, session);
        if (cards.isEmpty()) {
            return Optional.empty();
        }
        List<Section> sections = cards.stream().map(card -> new Section(card.attr(RUN), card.attr(HEADING), card.text())).toList();
        Instant updated = cards.stream().map(MemoryItem::updated).max(Comparator.naturalOrder()).orElseThrow();
        String title = title(cards);
        return Optional.of(new Note(day, session, title, render(day, session, title, sections), sections, updated));
    }

    /**
     * 用户改过的全文整篇换掉：按轮次对上原来的卡，改了的写新版本，删掉的留墓碑，没有轮次的新段算你写的。笔记不存在时返回 false。
     */
    public synchronized boolean write(String day, String session, String text) {
        check(day, session);
        List<MemoryItem> cards = cards(day, session);
        if (cards.isEmpty()) {
            return false;
        }
        LegacyNotes.Parsed parsed = LegacyNotes.parse(text);
        Map<String, MemoryItem> byRun = new HashMap<>();
        List<MemoryItem> withoutRun = new ArrayList<>();
        for (MemoryItem card : cards) {
            if (card.attr(RUN) != null) {
                byRun.put(card.attr(RUN), card);
            } else {
                withoutRun.add(card);
            }
        }
        String title = parsed.title().isEmpty() ? title(cards) : cleanTitle(parsed.title());
        List<MemoryItem> kept = new ArrayList<>();
        int unnamed = 0;
        for (LegacyNotes.Section section : parsed.sections()) {
            MemoryItem existing = section.run() != null ? byRun.remove(section.run())
                    : unnamed < withoutRun.size() ? withoutRun.get(unnamed++) : null;
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put(DAY, day);
            attrs.put(SESSION, session);
            attrs.put(TITLE, title);
            if (section.run() != null) {
                attrs.put(RUN, section.run());
            }
            attrs.put(HEADING, section.heading());
            if (existing == null) {
                MemoryItem card = MemoryItem.create(ledger.newId(), MemoryItem.Kind.CARD, MemoryItem.Source.USER_SAID,
                        MemoryItem.Actor.USER, section.body(), ledger.clock().instant()).withAttrs(attrs);
                ledger.put(card, 0);
            } else {
                kept.add(existing);
                if (!existing.text().equals(section.body()) || !existing.attrs().equals(attrs)) {
                    ledger.put(existing.withText(section.body()).withAttrs(attrs).withActor(MemoryItem.Actor.USER), existing.version());
                }
            }
        }
        for (MemoryItem card : cards) {
            if (!kept.contains(card)) {
                ledger.tombstone(card.id(), MemoryItem.Actor.USER, "removed from the note");
            }
        }
        return true;
    }

    /** 删掉一整篇；记过的轮次留在墓碑里。笔记不存在时返回 false。 */
    public synchronized boolean delete(String day, String session) {
        check(day, session);
        List<MemoryItem> cards = cards(day, session);
        if (cards.isEmpty()) {
            return false;
        }
        cards.forEach(card -> ledger.tombstone(card.id(), MemoryItem.Actor.USER, "note deleted"));
        return true;
    }

    private List<MemoryItem> cards(String day, String session) {
        return ledger.items(item -> isCard(item, day, session) && item.state() != MemoryItem.State.WITHDRAWN);
    }

    private static boolean isCard(MemoryItem item, String day, String session) {
        return item.kind() == MemoryItem.Kind.CARD && day.equals(item.attr(DAY)) && session.equals(item.attr(SESSION));
    }

    private static String title(List<MemoryItem> cards) {
        String title = cards.getFirst().attr(TITLE);
        return title == null ? "" : title;
    }

    private static String render(String day, String session, String title, List<Section> sections) {
        return LegacyNotes.render(day, session, title,
                sections.stream().map(section -> new LegacyNotes.Section(section.run(), section.heading(), section.body())).toList());
    }

    /** 标题一行、不含双引号（和旧格式的文件头一样）。 */
    private static String cleanTitle(String title) {
        return title == null ? "" : title.replaceAll("\\s+", " ").strip().replace("\"", "'");
    }

    private static String cut(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "…";
    }

    private static void check(String day, String session) {
        if (!validDay(day)) {
            throw new IllegalArgumentException("invalid day: " + day);
        }
        if (!validSession(session)) {
            throw new IllegalArgumentException("invalid session: " + session);
        }
    }
}
