package tantalum.memory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.MemoryItem;
import tantalum.memory.recall.Overlap;

/**
 * 遗忘：“不再使用”（memory-final-plan 第 10 节①）。程序只找候选（{@link #candidates}），哪几处真的在说这件事由模型或用户定，
 * 程序照选中的去掉那几行（{@link #forget}）：条目空了留墓碑，别的行留着。整条删掉时，同一轮照同一句原话记出来的别的条目和
 * 顺着 {@code derivedFrom} 衍生的一起标掉（出处相同是事实）。你认可过的决定，只有你本人操作时才动。
 * 没被选中的就是留下的：找回和搜索不再按“和忘掉的话像不像”去过滤。对话里的原话见 {@link Talk}。
 */
public final class Forgetting {

    static final String REASON = "forgotten";

    private Forgetting() {}

    /**
     * 整条删掉以后连带标掉的条目数：同一轮、照同一句原话记出来的别的条目，和顺着 {@code derivedFrom} 衍生的。这两样是出处上的事实。
     * 别处“复述它的”（当天的笔记、工作记忆、别的条目）不在这里猜：{@link #candidates} 把可能的列出来，由模型或用户选。
     */
    public static int cascade(Ledger ledger, List<MemoryItem> forgotten, MemoryItem.Actor actor) {
        Set<String> ids = new HashSet<>();
        List<String> texts = new ArrayList<>();
        Set<String> utterances = new HashSet<>();
        for (MemoryItem item : forgotten) {
            ids.add(item.id());
            if (item.text() != null && !item.text().isBlank()) {
                texts.add(item.text());
            }
            utterance(item).ifPresent(utterances::add);
        }
        int touched = 0;
        // 同一轮、同一句原话记出来的别的条目：模型换了个说法写的同一件事（词对不上，但出处是同一句话）
        if (!utterances.isEmpty()) {
            for (MemoryItem item : ledger.items(each -> each.state() == MemoryItem.State.CONFIRMED && !ids.contains(each.id())
                    && (each.kind() == MemoryItem.Kind.CLAIM || WorkingMemory.KINDS.contains(each.kind()))
                    && utterance(each).filter(utterances::contains).isPresent())) {
                if (guarded(item, actor)) {
                    continue;
                }
                if (ledger.tombstone(item.id(), actor, REASON + ": same words")) {
                    ids.add(item.id());
                    texts.add(item.text());
                    touched++;
                }
            }
        }
        // 衍生的（可能一层套一层）：每天整理照笔记提的条目记着是从哪张卡、哪条说法来的
        boolean grew = true;
        while (grew) {
            grew = false;
            for (MemoryItem item : ledger.items(each -> each.derivedFrom().stream().anyMatch(ids::contains))) {
                if (!guarded(item, actor) && ledger.tombstone(item.id(), actor, REASON + ": derived")) {
                    ids.add(item.id());
                    texts.add(item.text());
                    touched++;
                    grew = true;
                }
            }
        }
        return touched;
    }

    /** 这一条是哪一轮、照哪句原话记的；没有出处或没有原话时为空。 */
    private static java.util.Optional<String> utterance(MemoryItem item) {
        if (item.provenance() == null || item.provenance().run() == null || item.quote() == null) {
            return java.util.Optional.empty();
        }
        String quote = tantalum.memory.capture.Quotes.normalize(item.quote());
        return quote.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(item.provenance().run() + "\n" + quote);
    }

    /** 你认可过的决定，模型动不了（memory-final-plan 第 4 节）：只有你自己能忘掉它。 */
    private static boolean guarded(MemoryItem item, MemoryItem.Actor actor) {
        return actor != MemoryItem.Actor.USER && item.status() == MemoryItem.Status.APPROVED && item.live();
    }

    // ---- 按选中的忘（2026-11-19）：程序只找候选，哪些算由模型或用户定，程序照选中的执行 ----

    /** 找候选时的门槛：比做决定时宽，多给少漏；选不选不由它定。 */
    static final double LOOSE = 0.3;
    static final int MAX_PLACES = 20;

    /**
     * 账上的一处：某条目当前版本的某一行。{@code id} 是 {@code 条目号@版本#第几行}，执行时条目变过（版本对不上）就不动。
     * {@code approved}：这一处在你认可过的决定里，模型动不了。
     */
    public record Place(String id, MemoryItem.Kind kind, String page, String text, boolean approved) {}

    private static boolean forgettable(MemoryItem item) {
        return item.state() == MemoryItem.State.CONFIRMED
                && (item.kind() == MemoryItem.Kind.CLAIM || item.kind() == MemoryItem.Kind.CARD || WorkingMemory.KINDS.contains(item.kind()));
    }

    /**
     * 可能说的是这句话的地方：生效的说法、当天卡、工作记忆里和它词重合达到 {@link #LOOSE} 的行，加上和命中的条目同一轮、
     * 照同一句原话记出来的别的条目（出处相同是事实，不是猜）。只是检索：重合得多的在前，最多 {@link #MAX_PLACES} 处。
     */
    public static List<Place> candidates(Ledger ledger, String text) {
        record Scored(Place place, double score) {}
        List<Scored> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> utterances = new HashSet<>();
        List<MemoryItem> items = ledger.items(Forgetting::forgettable);
        for (MemoryItem item : items) {
            String[] lines = item.text().split("\n", -1);
            for (int n = 0; n < lines.length; n++) {
                double score = lines[n].isBlank() ? 0 : Overlap.of(lines[n], text);
                if (score >= LOOSE) {
                    Place place = place(item, n, lines[n]);
                    if (seen.add(place.id())) {
                        found.add(new Scored(place, score));
                    }
                    utterance(item).ifPresent(utterances::add);
                }
            }
        }
        for (MemoryItem item : items) {
            if (item.kind() == MemoryItem.Kind.CARD || utterance(item).filter(utterances::contains).isEmpty()) {
                continue;
            }
            String[] lines = item.text().split("\n", -1);
            for (int n = 0; n < lines.length; n++) {
                if (!lines[n].isBlank()) {
                    Place place = place(item, n, lines[n]);
                    if (seen.add(place.id())) {
                        found.add(new Scored(place, 0));
                    }
                }
            }
        }
        return found.stream().sorted((a, b) -> Double.compare(b.score(), a.score())).limit(MAX_PLACES).map(Scored::place).toList();
    }

    private static Place place(MemoryItem item, int line, String text) {
        return new Place(item.id() + "@" + item.version() + "#" + line, item.kind(), item.page(), text.strip(),
                item.status() == MemoryItem.Status.APPROVED && item.live());
    }

    /**
     * 照选中的执行的结果：{@code removed} 是去掉的原文；{@code stale} 是没动的编号（条目变过或已经不在，要重找）；
     * {@code kept} 是因为在你认可过的决定里、不是你本人操作而没动的编号。
     */
    public record Chosen(List<String> removed, List<String> stale, List<String> kept) {}

    /** 只去掉选中的那几行：条目空了留墓碑，页面上一条生效的说法都不剩时页面也留墓碑。别的一概不动。 */
    public static Chosen forget(Ledger ledger, List<String> places, MemoryItem.Actor actor) {
        java.util.Map<String, Set<Integer>> byItem = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> versions = new java.util.HashMap<>();
        List<String> stale = new ArrayList<>();
        for (String id : places) {
            java.util.regex.Matcher parsed = PLACE.matcher(id == null ? "" : id.strip());
            if (!parsed.matches()) {
                stale.add(String.valueOf(id));
                continue;
            }
            Integer version = Integer.valueOf(parsed.group(2));
            if (versions.merge(parsed.group(1), version, (a, b) -> a.equals(b) ? a : -1) == -1) {
                stale.add(id);
                continue;
            }
            byItem.computeIfAbsent(parsed.group(1), key -> new java.util.TreeSet<>()).add(Integer.valueOf(parsed.group(3)));
        }
        List<String> removed = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        Set<String> pages = new HashSet<>();
        // 每条动过的条目去掉了哪些字（规范化后）；整条去掉的另记
        java.util.Map<String, String> goneFrom = new java.util.HashMap<>();
        Set<String> whole = new HashSet<>();
        for (var entry : byItem.entrySet()) {
            MemoryItem item = ledger.get(entry.getKey()).filter(Forgetting::forgettable).orElse(null);
            int version = versions.get(entry.getKey());
            List<String> ids = entry.getValue().stream().map(line -> entry.getKey() + "@" + version + "#" + line).toList();
            String[] lines = item == null ? new String[0] : item.text().split("\n", -1);
            if (item == null || item.version() != version || entry.getValue().stream().anyMatch(line -> line >= lines.length || lines[line].isBlank())) {
                stale.addAll(ids);
                continue;
            }
            if (guarded(item, actor)) {
                kept.addAll(ids);
                continue;
            }
            List<String> rest = new ArrayList<>();
            List<String> gone = new ArrayList<>();
            for (int n = 0; n < lines.length; n++) {
                (entry.getValue().contains(n) ? gone : rest).add(lines[n]);
            }
            String left = String.join("\n", rest).strip();
            boolean done = left.isEmpty() ? ledger.tombstone(item.id(), actor, REASON)
                    : ledger.put(item.withText(left).withActor(actor), item.version()) instanceof Ledger.Result.Written;
            if (!done) {
                stale.addAll(ids);
                continue;
            }
            gone.forEach(line -> removed.add(line.strip()));
            goneFrom.put(item.id(), tantalum.memory.capture.Quotes.normalize(String.join("\n", gone)));
            if (left.isEmpty()) {
                whole.add(item.id());
            }
            if (!left.isEmpty()) {
                // 只去掉几行时条目还在，另留一个墓碑记下去掉的是什么、什么时候
                MemoryItem said = MemoryItem.create(ledger.newId(), MemoryItem.Kind.CLAIM, MemoryItem.Source.USER_SAID, actor,
                        String.join("\n", gone).strip(), ledger.clock().instant());
                ledger.put(said, 0);
                ledger.tombstone(said.id(), actor, REASON);
            }
            if (item.kind() == MemoryItem.Kind.CLAIM && item.page() != null) {
                pages.add(item.page());
            }
        }
        // 从去掉的这些字衍生出来的（每天整理照笔记里的原话提的条目，记着出处和那句原话）：出处没了，跟着去掉。
        // 有原话的看原话在不在去掉的字里；没有原话的要出处整条没了才算。这是账上记着的出处，不是猜
        if (!goneFrom.isEmpty()) {
            for (MemoryItem derived : ledger.items(each -> each.derivedFrom().stream().anyMatch(goneFrom::containsKey))) {
                String quote = derived.quote() == null ? "" : tantalum.memory.capture.Quotes.normalize(derived.quote());
                boolean orphaned = derived.derivedFrom().stream().anyMatch(source -> quote.isEmpty() ? whole.contains(source)
                        : goneFrom.getOrDefault(source, "").contains(quote));
                if (orphaned && !guarded(derived, actor) && ledger.tombstone(derived.id(), actor, REASON + ": derived")) {
                    removed.add(derived.text().strip());
                    if (derived.kind() == MemoryItem.Kind.CLAIM && derived.page() != null) {
                        pages.add(derived.page());
                    }
                }
            }
        }
        tidyPages(ledger, pages, actor);
        return new Chosen(List.copyOf(removed), List.copyOf(stale), List.copyOf(kept));
    }

    private static final java.util.regex.Pattern PLACE = java.util.regex.Pattern.compile("(.+)@(\\d{1,9})#(\\d{1,6})");

    /** 页面：改过的版本加一（控制台里开着旧版本的人保存时会被拦下）；一条生效的说法都不剩的留墓碑。 */
    private static void tidyPages(Ledger ledger, Set<String> pages, MemoryItem.Actor actor) {
        for (MemoryItem page : ledger.items(item -> item.kind() == MemoryItem.Kind.PAGE && item.state() == MemoryItem.State.CONFIRMED
                && pages.contains(item.page()))) {
            boolean empty = ledger.items(item -> item.kind() == MemoryItem.Kind.CLAIM && page.page().equals(item.page())
                    && (item.live() || item.pendingConfirm() && item.state() == MemoryItem.State.CONFIRMED)).isEmpty();
            if (empty) {
                ledger.tombstone(page.id(), actor, REASON);
            } else {
                ledger.put(page.withActor(actor), page.version());
            }
        }
    }
}
