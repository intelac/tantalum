package tantalum.memory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tantalum.memory.capture.Quotes;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.MemoryItem;

/**
 * 图书管理员的提议（memory-final-plan 第 9 节）：模型读最近 7 天的当天卡、档案的目录和 7 天没动的工作记忆，提议三种事——
 * 往某一页加一条说法、改正一条旧说法、关掉一件过期的事。<b>全部只作为提议</b>，进“待确认”，你确认后才生效（比方案保守：方案里新建、补充可以直接做）。
 * 加的和改正的要带原话，代码在最近的当天卡里找得到才收；没有工具，不读文件、不联网。
 */
public final class LibrarianProposals {

    /** 调模型：system、给模型的文字 → 回答；失败为空。 */
    @FunctionalInterface
    public interface Model {
        Optional<String> ask(String system, String text);
    }

    /** 这次收下了几条提议。 */
    public record Result(int added, int corrections, int closes) {}

    public static final String PROPOSAL = "proposal";
    public static final String CLOSE = "close";
    public static final String TARGET = "target";
    static final Duration RECENT = Duration.ofDays(7);
    static final int MAX_CARDS = 40;
    static final int MAX = 5;

    static final String SYSTEM = """
            You tidy one user's long-term memory. You get the user's daily notes from the last days, the list of memory entries \
            with their statements, and the open work items that have not changed for a week. Propose at most 5 changes, one per \
            line, only when the notes clearly support them:
            - [add page-name] the statement «exact words from the notes»      add a statement to an existing or new entry
            - [fix statement-id] the corrected statement «exact words from the notes»      a newer note contradicts an old statement
            - [close item-id] why it is probably done or dropped
            The «exact words» must be copied character for character from the notes. Do not repeat what an entry already says. \
            Do not include passwords, keys, ID numbers or phone numbers. If nothing needs to change, reply exactly NOTHING.""";

    private static final Pattern LINE = Pattern.compile("^[-*•]\\s*\\[(add|fix|close)\\s+([A-Za-z0-9_-]{1,64})\\]\\s*(.+?)\\s*(?:«([^»]+)»)?\\s*$");

    private final Ledger ledger;
    private final MemoryStore store;
    private final WorkingMemory working;
    private final ZoneId zone;

    public LibrarianProposals(Ledger ledger, MemoryStore store, WorkingMemory working, ZoneId zone) {
        this.ledger = ledger;
        this.store = store;
        this.working = working;
        this.zone = zone;
    }

    /** 问一次模型，收下合格的提议。没有最近的笔记、也没有过期的事时不问。 */
    public Result propose(Model model) {
        Instant now = ledger.clock().instant();
        LocalDate since = LocalDate.ofInstant(now.minus(RECENT), zone);
        List<MemoryItem> cards = ledger.items(item -> item.kind() == MemoryItem.Kind.CARD && item.live() && item.attr("day") != null
                && !LocalDate.parse(item.attr("day")).isBefore(since));
        List<MemoryItem> stale = working.open().stream().filter(item -> Duration.between(item.updated(), now).compareTo(RECENT) > 0).toList();
        if (cards.isEmpty() && stale.isEmpty()) {
            return new Result(0, 0, 0);
        }
        StringBuilder text = new StringBuilder("Daily notes:\n");
        cards.stream().skip(Math.max(0, cards.size() - MAX_CARDS)).forEach(card -> text.append(card.attr("day")).append('\n')
                .append(card.text()).append('\n'));
        text.append("\nMemory entries:\n");
        for (MemoryEntry entry : store.list()) {
            text.append("## ").append(entry.name()).append(" — ").append(entry.description()).append('\n');
            ledger.items(item -> item.kind() == MemoryItem.Kind.CLAIM && item.live() && entry.name().equals(item.page()))
                    .forEach(claim -> text.append("- [").append(claim.id()).append("] ").append(claim.text().replaceAll("\\s+", " ")).append('\n'));
        }
        text.append("\nWork items unchanged for a week:\n");
        stale.forEach(item -> text.append("- [").append(item.id()).append("] ").append(item.text()).append(" (since ")
                .append(LocalDate.ofInstant(item.updated(), zone)).append(")\n"));
        Optional<String> reply = model.ask(SYSTEM, text.toString());
        if (reply.isEmpty()) {
            return new Result(0, 0, 0);
        }
        String notes = cards.stream().map(MemoryItem::text).reduce("", (a, b) -> a + "\n" + b);
        int added = 0;
        int fixed = 0;
        int closed = 0;
        int taken = 0;
        for (String line : reply.get().lines().map(String::strip).toList()) {
            Matcher matcher = LINE.matcher(line);
            if (taken >= MAX || !matcher.matches()) {
                continue;
            }
            String action = matcher.group(1);
            String target = matcher.group(2);
            String body = matcher.group(3).strip();
            String quote = matcher.group(4);
            if (CredentialGuard.detect(body).isPresent()) {
                continue;
            }
            switch (action) {
                case "add" -> {
                    if (supported(quote, notes) && pendingClaim(target, body, quote, Map.of())) {
                        added++;
                        taken++;
                    }
                }
                case "fix" -> {
                    Optional<MemoryItem> old = ledger.get(target).filter(item -> item.kind() == MemoryItem.Kind.CLAIM && item.live());
                    if (old.isPresent() && supported(quote, notes) && pendingClaim(old.get().page(), body, quote, Map.of("supersedes", target))) {
                        fixed++;
                        taken++;
                    }
                }
                default -> {
                    Optional<MemoryItem> item = stale.stream().filter(each -> each.id().equals(target)).findFirst();
                    if (item.isPresent() && working.add(MemoryItem.Kind.NOTICED, "Close: " + item.get().text() + " — " + body,
                            Map.of(PROPOSAL, CLOSE, TARGET, target), MemoryItem.Source.ASSISTANT_INFERRED, MemoryItem.Actor.LIBRARIAN, null,
                            null, true, null).isPresent()) {
                        closed++;
                        taken++;
                    }
                }
            }
        }
        return new Result(added, fixed, closed);
    }

    /** 原话要在最近的笔记里找得到（规范化后）。 */
    private static boolean supported(String quote, String notes) {
        String wanted = Quotes.normalize(quote);
        return wanted.length() >= 2 && Quotes.normalize(notes).contains(wanted);
    }

    private boolean pendingClaim(String page, String body, String quote, Map<String, String> attrs) {
        if (!MemoryFormat.isValidName(page)) {
            return false;
        }
        Map<String, String> all = new LinkedHashMap<>(attrs);
        all.put(PROPOSAL, "librarian");
        MemoryItem claim = MemoryItem.create(ledger.newId(), MemoryItem.Kind.CLAIM, MemoryItem.Source.ASSISTANT_INFERRED,
                MemoryItem.Actor.LIBRARIAN, body, ledger.clock().instant()).withQuote(quote).withPendingConfirm(true).withAttrs(all);
        String description = store.read(page).map(MemoryEntry::description).orElse("Notes the librarian proposed");
        String type = store.read(page).map(entry -> entry.type().pageType()).orElse("term");
        return store.addClaim(page, type, description, claim).isPresent();
    }

}
