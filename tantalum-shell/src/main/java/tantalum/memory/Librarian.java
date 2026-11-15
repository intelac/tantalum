package tantalum.memory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tantalum.memory.capture.Quotes;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.MemoryItem;
import tantalum.memory.recall.Recall;

/**
 * 图书管理员（memory-final-plan 第 9 节）的第一版：每人每天一次，只做不改变意思的整理——同一页上意思一样的生效说法（规范化后相同）
 * 合成一条：留最早的，记下最后一次印证的时刻，其余标成被它取代（不是墓碑，能撤回）。整理前后各跑一遍探测题（你亲口说的、生效的说法，
 * 用它自己的话去找），找到的变少就撤回这次整理。新建、纠正、关闭过期项这些要模型判断的，以后再加，而且只作为提议。
 */
public final class Librarian {

    static final String MARKER = "librarian:";

    /** 这一次整理：合并了几条、有没有撤回。 */
    public record Result(int merged, boolean rolledBack) {}

    private final Ledger ledger;
    private final ZoneId zone;

    public Librarian(Ledger ledger, ZoneId zone) {
        this.ledger = ledger;
        this.zone = zone;
    }

    /** 今天还没整理过就整理一次（记一个当天的标记）；整理过了返回 null。 */
    public synchronized Result daily() {
        String marker = MARKER + LocalDate.ofInstant(ledger.clock().instant(), zone);
        if (ledger.hasMarker(marker)) {
            return null;
        }
        Result result = tidy();
        ledger.mark(marker);
        return result;
    }

    synchronized Result tidy() {
        List<MemoryItem> probes = ledger.items(item -> item.kind() == MemoryItem.Kind.CLAIM && item.live()
                && item.source() == MemoryItem.Source.USER_SAID);
        int before = found(probes);
        Map<String, List<MemoryItem>> same = new HashMap<>();
        for (MemoryItem claim : ledger.items(item -> item.kind() == MemoryItem.Kind.CLAIM && item.live() && item.page() != null)) {
            same.computeIfAbsent(claim.page() + "\n" + Quotes.normalize(claim.text()), key -> new ArrayList<>()).add(claim);
        }
        List<MemoryItem> changed = new ArrayList<>();
        for (List<MemoryItem> group : same.values()) {
            if (group.size() < 2) {
                continue;
            }
            group.sort(Comparator.comparing(MemoryItem::created));
            MemoryItem kept = group.getFirst();
            MemoryItem newest = group.getLast();
            if (ledger.put(kept.withLastConfirmed(newest.created()).withActor(MemoryItem.Actor.LIBRARIAN), kept.version())
                    instanceof Ledger.Result.Written) {
                for (MemoryItem duplicate : group.subList(1, group.size())) {
                    if (ledger.put(duplicate.withSupersededBy(kept.id()).withActor(MemoryItem.Actor.LIBRARIAN), duplicate.version())
                            instanceof Ledger.Result.Written written) {
                        changed.add(written.item());
                    }
                }
            }
        }
        if (changed.isEmpty()) {
            return new Result(0, false);
        }
        if (found(probes) < before) {
            for (MemoryItem item : changed) {
                ledger.get(item.id()).ifPresent(current -> ledger.put(current.withSupersededBy(null), current.version()));
            }
            return new Result(changed.size(), true);
        }
        return new Result(changed.size(), false);
    }

    /** 探测题：每条用它自己的话去找，找到它（或取代它的那条）算一题对。 */
    private int found(List<MemoryItem> probes) {
        Recall recall = new Recall(ledger);
        int found = 0;
        for (MemoryItem probe : probes) {
            String target = Quotes.normalize(probe.text());
            if (recall.search(probe.text(), "", null, zone).stream().anyMatch(hit -> Quotes.normalize(hit.item().text()).equals(target))) {
                found++;
            }
        }
        return found;
    }
}
