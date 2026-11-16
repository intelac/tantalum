package tantalum.memory.recall;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tantalum.memory.MemoryStore;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.MemoryItem;

/**
 * 找回（memory-final-plan 第 9 节）：在一个人的记忆账上按关键词找说法和当天卡。
 * <ul>
 *   <li>范围：个人的，加上当前目录所在项目的（{@code project:<目录>}）；别的项目的不给。</li>
 *   <li>被取代的不给（{@code supersededBy}，或者另一条生效说法的 {@code attrs.supersedes} 指着它）；撤掉的、墓碑掉的不在账上。</li>
 *   <li>等你确认的照样找，但标明没确认，不能当事实。</li>
 *   <li>打分：命中词的 idf 之和；至少命中 min(2, 问题的词数) 个才算。问题里说了时间（“三天前”）就只在那几天里找，那几天的都算。</li>
 *   <li>问的是某段时间（“昨天做了什么”）时，要的是那几天的经过：当天卡优先，最多给 {@link #WINDOW_LIMIT} 条，按时间从早到晚排
 *   （同一天里调了又撤回，要看得出先后）。</li>
 * </ul>
 */
public final class Recall {

    /** 一条找回：条目、所在页面的说明（没有为 null）、分数。 */
    public record Hit(MemoryItem item, String pageDescription, double score) {}

    public static final int LIMIT = 6;
    public static final int WINDOW_LIMIT = 12;

    private final Ledger ledger;

    public Recall(Ledger ledger) {
        this.ledger = ledger;
    }

    /**
     * {@code question} 是你的问题，{@code extra} 是改写给的关键词（可以为空），{@code dir} 是会话的目录（没有为 null），
     * {@code zone} 算日子用。
     */
    /**
     * 要不要先改写问题（memory-final-plan 第 9 节第 2 步）：记忆是空的不改；问题里有指代（“那个”“第二个”）、抽不出关键词，
     * 或者问题和记忆不是一种文字（英文问、记的是中文，反过来也一样）而且一条都没找到时才改。
     */
    public boolean worthRewriting(String question, List<Hit> found, boolean deictic, String dir) {
        List<Candidate> candidates = candidates(dir);
        if (candidates.isEmpty()) {
            return false;
        }
        Set<String> asked = Terms.of(question);
        if (deictic || asked.isEmpty()) {
            return true;
        }
        if (!found.isEmpty()) {
            return false;
        }
        boolean askedCjk = asked.stream().anyMatch(Recall::cjk);
        long cjkItems = candidates.stream().filter(candidate -> candidate.terms.stream().anyMatch(Recall::cjk)).count();
        boolean memoryCjk = cjkItems * 2 >= candidates.size();
        return askedCjk != memoryCjk;
    }

    private static boolean cjk(String term) {
        return term.codePoints().anyMatch(Terms::isCjk);
    }

    public List<Hit> search(String question, String extra, String dir, ZoneId zone) {
        LocalDate today = LocalDate.ofInstant(ledger.clock().instant(), zone);
        Optional<TimeWindow> window = TimeWindow.in(question, today);
        Set<String> wanted = new HashSet<>(Terms.of(question));
        wanted.addAll(Terms.of(extra));
        List<Candidate> candidates = candidates(dir);
        Map<String, Integer> df = new HashMap<>();
        for (Candidate candidate : candidates) {
            candidate.terms.forEach(term -> df.merge(term, 1, Integer::sum));
        }
        int needed = Math.min(2, Math.max(1, Terms.of(question).size()));
        List<Hit> hits = new ArrayList<>();
        for (Candidate candidate : candidates) {
            LocalDate day = LocalDate.ofInstant(candidate.item.validFrom(), zone);
            boolean inWindow = window.isPresent() && window.get().contains(day);
            if (window.isPresent() && !inWindow) {
                continue;
            }
            double score = 0;
            int matched = 0;
            for (String term : wanted) {
                if (candidate.terms.contains(term)) {
                    matched++;
                    score += Math.log(1 + (double) candidates.size() / df.getOrDefault(term, 1));
                }
            }
            if (matched >= needed || inWindow) {
                // 当天卡是一轮的摘要，比页面上的说法弱一点；问某段时间时反过来，卡记的是那天的经过
                boolean card = candidate.item.kind() == MemoryItem.Kind.CARD;
                double weight = card ? (inWindow ? 1.5 : 0.8) : 1.0;
                hits.add(new Hit(candidate.item, candidate.description, weight * (score + (inWindow ? 1 : 0))));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(hit -> hit.item().validFrom(), Comparator.reverseOrder()));
        if (window.isPresent()) {
            List<Hit> picked = new ArrayList<>(hits.size() > WINDOW_LIMIT ? hits.subList(0, WINDOW_LIMIT) : hits);
            picked.sort(Comparator.comparing(hit -> hit.item().validFrom()));
            return List.copyOf(picked);
        }
        return hits.size() > LIMIT ? List.copyOf(hits.subList(0, LIMIT)) : List.copyOf(hits);
    }

    private record Candidate(MemoryItem item, String description, Set<String> terms) {}

    private List<Candidate> candidates(String dir) {
        Map<String, MemoryItem> pages = new HashMap<>();
        for (MemoryItem page : ledger.items(item -> item.kind() == MemoryItem.Kind.PAGE && item.state() == MemoryItem.State.CONFIRMED)) {
            pages.put(page.page(), page);
        }
        List<MemoryItem> live = ledger.items(item -> (item.kind() == MemoryItem.Kind.CLAIM || item.kind() == MemoryItem.Kind.CARD)
                && item.state() == MemoryItem.State.CONFIRMED && item.supersededBy() == null);
        Set<String> superseded = new HashSet<>();
        for (MemoryItem item : live) {
            if (!item.pendingConfirm() && item.attr("supersedes") != null) {
                superseded.add(item.attr("supersedes"));
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        List<tantalum.memory.Forgetting.Forgotten> gone = tantalum.memory.Forgetting.forgotten(ledger);
        for (MemoryItem item : live) {
            if (superseded.contains(item.id()) || !inScope(item, dir) || tantalum.memory.Forgetting.shadowed(gone, item)) {
                continue;
            }
            MemoryItem page = item.page() == null ? null : pages.get(item.page());
            if (item.kind() == MemoryItem.Kind.CLAIM && (page == null || page.pendingConfirm() && !item.pendingConfirm())) {
                continue;
            }
            String description = page == null ? null : page.attr(MemoryStore.DESCRIPTION);
            Set<String> terms = new HashSet<>(Terms.of(item.text()));
            terms.addAll(Terms.of(description));
            if (item.page() != null) {
                terms.addAll(Terms.of(item.page().replace('-', ' ')));
            }
            candidates.add(new Candidate(item, description, terms));
        }
        return candidates;
    }

    /** 个人的都在范围里；项目的只在那个目录（或它下面）的会话里。 */
    static boolean inScope(MemoryItem item, String dir) {
        String scope = item.scope();
        if (scope == null || scope.equals(MemoryItem.PERSONAL) || !scope.startsWith("project:")) {
            return true;
        }
        if (dir == null || dir.isBlank()) {
            return false;
        }
        Path project = Path.of(scope.substring("project:".length())).toAbsolutePath().normalize();
        return Path.of(dir).toAbsolutePath().normalize().startsWith(project);
    }
}
