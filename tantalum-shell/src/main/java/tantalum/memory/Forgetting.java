package tantalum.memory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.MemoryItem;
import tantalum.memory.recall.Overlap;

/**
 * 遗忘：“不再使用”（memory-final-plan 第 10 节①）。忘掉的说法留墓碑；同一轮、同一句原话记出来的别的条目和顺着
 * {@code derivedFrom} 衍生的一起标掉；多行的条目和当天卡里只删说它的那几行（别的留着；删空了整条留墓碑）；工作记忆里说的是它的也标掉。
 * 一律按行比，不把整条多行正文当一句话。你认可过的决定，只有你本人操作时才动。
 * 找回和写入时按和墓碑文字的相似度过滤（{@link #shadowed}），换说法问也找不回来，捕获也不会再把它记回去。
 */
public final class Forgetting {

    /** 一句话和忘掉的那句重合这么多（较短那句的词）就算同一件事。 */
    static final double SAME = 0.5;
    static final String REASON = "forgotten";

    private Forgetting() {}

    /** 这一行说的是不是忘掉的那句：按 memory-final-plan 第 9 节的词重合（较短一边的一半以上）。 */
    static boolean says(String line, String text) {
        return Overlap.of(line, text) >= SAME;
    }

    /**
     * 从一条多行的正文里去掉说这些话的那几行（2026-11-17 review：以前整条正文当一句话比，一句短话能带走整条多行条目）。
     * 没有一行对得上返回 null；全去掉了返回空串；否则返回剩下的。
     */
    static String without(String body, List<String> texts) {
        List<String> kept = new ArrayList<>();
        boolean dropped = false;
        for (String line : body.split("\n", -1)) {
            if (!line.isBlank() && texts.stream().anyMatch(text -> says(line, text))) {
                dropped = true;
            } else {
                kept.add(line);
            }
        }
        return dropped ? String.join("\n", kept).strip() : null;
    }

    /** 忘掉这些说法以后连带标掉的条目数（同一句原话记出来的、衍生的、卡里的行、工作记忆）。 */
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
        // 衍生的（可能一层套一层）。现在还没有哪里写 derivedFrom（图书管理员合并条目时才会有），先留着
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
        if (texts.isEmpty()) {
            return touched;
        }
        for (MemoryItem card : ledger.items(item -> item.kind() == MemoryItem.Kind.CARD && item.state() == MemoryItem.State.CONFIRMED)) {
            String rest = without(card.text(), texts);
            if (rest == null) {
                continue;
            }
            touched++;
            if (rest.isEmpty()) {
                ledger.tombstone(card.id(), actor, REASON + ": restated");
            } else {
                ledger.put(card.withText(rest).withActor(actor), card.version());
            }
        }
        for (MemoryItem item : ledger.items(each -> WorkingMemory.KINDS.contains(each.kind()) && each.state() == MemoryItem.State.CONFIRMED)) {
            if (texts.stream().anyMatch(text -> says(item.text(), text)) && ledger.tombstone(item.id(), actor, REASON)) {
                touched++;
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

    /** 忘掉的结果：去掉了几处（说法、页面上的行），和因为是你认可过的决定而没动的那几条。 */
    /** {@code removed} 是去掉的原文：整条去掉的是那条说法的全文，只去掉几行的是那几行。给调用的一方照实看到带走了什么。 */
    public record Result(int gone, List<MemoryItem> kept, List<String> removed) {

    }

    /**
     * 按一句话忘掉（接口 {@code POST forget}）：生效的说法里说这句话的那几行去掉（多行的条目别的行留着，去空了整条留墓碑，页面空了
     * 一起留墓碑），再连带标掉同一句原话记出来的、衍生的、卡里复述的、工作记忆里的。返回去掉的处数。
     */
    public static int forget(Ledger ledger, String text, MemoryItem.Actor actor) {
        return forgetting(ledger, text, actor).gone();
    }

    /** 同 {@link #forget}，并说明哪些认可过的决定没动（不是你本人操作时）。 */
    public static Result forgetting(Ledger ledger, String text, MemoryItem.Actor actor) {
        List<String> wanted = List.of(text);
        List<MemoryItem> gone = new ArrayList<>();
        List<MemoryItem> kept = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        Set<String> pages = new HashSet<>();
        int places = 0;
        for (MemoryItem claim : ledger.items(item -> item.kind() == MemoryItem.Kind.CLAIM && item.state() == MemoryItem.State.CONFIRMED)) {
            String rest = without(claim.text(), wanted);
            if (rest == null) {
                continue;
            }
            if (guarded(claim, actor)) {
                kept.add(claim);
                continue;
            }
            if (rest.isEmpty()) {
                if (ledger.tombstone(claim.id(), actor, REASON)) {
                    gone.add(claim);
                    removed.add(claim.text().strip());
                    places++;
                }
            } else if (ledger.put(claim.withText(rest).withActor(actor), claim.version()) instanceof Ledger.Result.Written) {
                Set<String> left = new HashSet<>(rest.lines().map(String::strip).toList());
                removed.add(String.join("\n", claim.text().lines().map(String::strip).filter(line -> !line.isEmpty() && !left.contains(line)).toList()));
                places++;
            }
            if (claim.page() != null) {
                pages.add(claim.page());
            }
        }
        // 页面：改过的版本加一（控制台里开着旧版本的人保存时会被拦下）；一条生效的说法都不剩的留墓碑
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
        // 只说了一句话、账上没有对应说法时，卡里和工作记忆里复述它的也要去掉
        MemoryItem said = MemoryItem.create("forget-" + ledger.newId(), MemoryItem.Kind.CLAIM, MemoryItem.Source.USER_SAID, actor, text,
                ledger.clock().instant());
        List<MemoryItem> cascadeFrom = new ArrayList<>(gone);
        cascadeFrom.add(said);
        cascade(ledger, cascadeFrom, actor);
        return new Result(places, kept, List.copyOf(removed));
    }

    /**
     * 对话里用户说“这条忘掉吧”（2026-11-16 真模型端到端：模型只会整条删或者改写页面，改写不留墓碑，那句话还会从笔记、工作记忆、
     * 会话标题里回来）：照 {@link #forgetting} 按一句话忘掉，并给这句话本身留一个墓碑，以后的找回、笔记、标题都按它过滤。
     * 你认可过的决定不动（模型替你忘的时候），结果里说明。
     */
    public static Result statement(MemoryStore store, String text, MemoryItem.Actor actor) {
        Ledger ledger = store.ledger();
        Result result;
        synchronized (store) {
            result = forgetting(ledger, text, actor);
        }
        MemoryItem said = MemoryItem.create(ledger.newId(), MemoryItem.Kind.CLAIM, MemoryItem.Source.USER_SAID, actor, text, ledger.clock().instant());
        ledger.put(said, 0);
        ledger.tombstone(said.id(), actor, REASON);
        return result;
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
        // 你认可过的决定不会被模型替你忘掉的一句话盖住（要忘它得你自己来，那时它本身就留了墓碑）
        if (item.status() == MemoryItem.Status.APPROVED && item.live()) {
            return false;
        }
        List<String> lines = item.text().lines().filter(line -> !line.isBlank()).toList();
        return forgotten.stream().anyMatch(gone -> item.created().isBefore(gone.at()) && !lines.isEmpty()
                && lines.stream().allMatch(line -> says(line, gone.text())));
    }
}
