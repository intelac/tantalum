package tantalum.memory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.MemoryItem;
import tantalum.memory.recall.Overlap;

/**
 * 遗忘：“不再使用”（memory-final-plan 第 10 节①）。忘掉的说法留墓碑；顺着 {@code derivedFrom} 把衍生的一起标掉；
 * 当天卡里复述过它的那几行删掉（卡改写一版，别的要点留着；删空了整张留墓碑）；工作记忆里说的是它的也标掉。
 * 找回和写入时按和墓碑文字的相似度过滤（{@link #shadowed}），换说法问也找不回来，捕获也不会再把它记回去。
 */
public final class Forgetting {

    /** 一句话和忘掉的那句重合这么多（较短那句的词）就算同一件事。 */
    static final double SAME = 0.5;
    static final String REASON = "forgotten";

    private Forgetting() {}

    /** 忘掉这些说法以后连带标掉的条目数（衍生的、卡里的行、工作记忆）。 */
    public static int cascade(Ledger ledger, List<MemoryItem> forgotten, MemoryItem.Actor actor) {
        Set<String> ids = new HashSet<>();
        List<String> texts = new ArrayList<>();
        for (MemoryItem item : forgotten) {
            ids.add(item.id());
            if (item.text() != null && !item.text().isBlank()) {
                texts.add(item.text());
            }
        }
        int touched = 0;
        // 衍生的（可能一层套一层）
        boolean grew = true;
        while (grew) {
            grew = false;
            for (MemoryItem item : ledger.items(each -> each.derivedFrom().stream().anyMatch(ids::contains))) {
                if (ledger.tombstone(item.id(), actor, REASON + ": derived")) {
                    ids.add(item.id());
                    texts.add(item.text());
                    touched++;
                    grew = true;
                }
            }
        }
        if (texts.isEmpty()) {
            return touched;
        }
        for (MemoryItem card : ledger.items(item -> item.kind() == MemoryItem.Kind.CARD && item.state() == MemoryItem.State.CONFIRMED)) {
            List<String> kept = new ArrayList<>();
            boolean dropped = false;
            for (String line : card.text().split("\n", -1)) {
                if (!line.isBlank() && texts.stream().anyMatch(text -> Overlap.of(line, text) >= SAME)) {
                    dropped = true;
                } else {
                    kept.add(line);
                }
            }
            if (!dropped) {
                continue;
            }
            touched++;
            String rest = String.join("\n", kept).strip();
            if (rest.isEmpty()) {
                ledger.tombstone(card.id(), actor, REASON + ": restated");
            } else {
                ledger.put(card.withText(rest).withActor(actor), card.version());
            }
        }
        for (MemoryItem item : ledger.items(each -> WorkingMemory.KINDS.contains(each.kind()) && each.state() == MemoryItem.State.CONFIRMED)) {
            if (texts.stream().anyMatch(text -> Overlap.of(item.text(), text) >= SAME) && ledger.tombstone(item.id(), actor, REASON)) {
                touched++;
            }
        }
        return touched;
    }

    /**
     * 按一句话忘掉（接口 {@code POST forget}）：和它重合一半以上的生效说法留墓碑，再连带标掉衍生的、卡里复述的、工作记忆里的。
     * 返回忘掉的说法条数。
     */
    public static int forget(Ledger ledger, String text, MemoryItem.Actor actor) {
        List<MemoryItem> matching = ledger.items(item -> item.kind() == MemoryItem.Kind.CLAIM && item.state() == MemoryItem.State.CONFIRMED
                && Overlap.of(item.text(), text) >= SAME);
        List<MemoryItem> gone = new ArrayList<>();
        for (MemoryItem claim : matching) {
            if (ledger.tombstone(claim.id(), actor, REASON)) {
                gone.add(claim);
            }
        }
        // 只说了一句话、账上没有对应说法时，卡里和工作记忆里复述它的也要去掉
        MemoryItem said = MemoryItem.create("forget-" + ledger.newId(), MemoryItem.Kind.CLAIM, MemoryItem.Source.USER_SAID, actor, text,
                ledger.clock().instant());
        List<MemoryItem> cascadeFrom = new ArrayList<>(gone);
        cascadeFrom.add(said);
        cascade(ledger, cascadeFrom, actor);
        return gone.size();
    }

    /** 忘掉的一句：原文和忘掉的时刻。 */
    public record Forgotten(String text, java.time.Instant at) {}

    /** 忘掉过的说法（墓碑掉的页面上的说法、衍生的）。 */
    public static List<Forgotten> forgotten(Ledger ledger) {
        List<Forgotten> gone = new ArrayList<>();
        for (MemoryItem item : ledger.everything(each -> each.kind() == MemoryItem.Kind.CLAIM && ledger.tombstoned(each.id()))) {
            if (item.text() != null && !item.text().isBlank()) {
                gone.add(new Forgotten(item.text(), ledger.tombstonedAt(item.id()).orElse(item.updated())));
            }
        }
        return gone;
    }

    /**
     * 这一条说的是忘掉过的事，而且是在忘掉之前就有的（旧的副本）。忘掉以后你又说了一遍的，是新的，不算（比如换了新车牌）。
     */
    public static boolean shadowed(List<Forgotten> forgotten, MemoryItem item) {
        return forgotten.stream().anyMatch(gone -> item.created().isBefore(gone.at()) && Overlap.of(gone.text(), item.text()) >= SAME);
    }
}
