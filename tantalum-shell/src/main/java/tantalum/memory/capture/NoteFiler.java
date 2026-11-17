package tantalum.memory.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.memory.DailyNotes;
import tantalum.memory.MemoryStore;
import tantalum.memory.WorkingMemory;
import tantalum.memory.ledger.MemoryItem;
import tantalum.shell.plugin.RunSegment;
import tantalum.shell.plugin.SensitiveText;

/**
 * 把一轮的笔记归档（memory-final-plan 第 7 节）：
 * <ol>
 *   <li>原话在你的话里找到的（你亲口说的；这一轮读过外部内容时要点本身也得逐字出自你的话，见 {@link Quotes#match}）直接生效：偏好、事实、决定写成页面上的说法，在做的事、答应的事、在等的事进工作记忆，都进当天卡；</li>
 *   <li>原话只在回答里的（推断）：这一轮没读过外部内容时只进当天卡；读过的话，偏好、事实、决定、事项记成“待你确认”，不进当天卡；</li>
 *   <li>原话哪里都找不到的、命中敏感信息检测的，丢掉。</li>
 * </ol>
 * 另外两件不靠模型判断真假的事：
 * <ul>
 *   <li>当天卡开头逐字记下这一轮你说的话（{@code > } 开头，截短）。你的话是可信的，所以读过外部内容的那一轮也记；
 *   “昨天做了什么”靠它（模型的小结在这种轮次里不进卡）。</li>
 *   <li>“了结”（{@link NotePrompt.Kind#DONE}）：把工作记忆里一项开着的事标成完成，原话在这一轮里找得到就算。它不新增任何说法，
 *   模型在运行中用 {@code now} 也能做同样的事，所以不分是不是你亲口说的。</li>
 * </ul>
 */
public final class NoteFiler {

    private static final Logger LOG = LoggerFactory.getLogger(NoteFiler.class);

    /** 默认放这几种说法的页面：名字、类型、说明。 */
    record Shelf(String page, String type, String description) {}

    static final Shelf PREFERENCES = new Shelf("preferences", "preference", "How the user wants things done");
    static final Shelf ABOUT = new Shelf("about-me", "person", "Facts about the user and their work");
    static final Shelf DECISIONS = new Shelf("decisions", "decision", "Decisions made and why");

    /** 归档要用的：这一轮在哪、它的分段、读没读过外部内容，以及写到哪。 */
    public record Turn(String session, String run, String day, String title, String heading, List<RunSegment> segments, boolean tainted) {}

    /** 结果：生效几条、待确认几条、丢掉几条。 */
    public record Filed(int kept, int pending, int dropped) {}

    private final MemoryStore store;
    private final WorkingMemory working;
    private final DailyNotes daily;
    private final SensitiveText sensitive;

    public NoteFiler(MemoryStore store, WorkingMemory working, DailyNotes daily, SensitiveText sensitive) {
        this.store = store;
        this.working = working;
        this.daily = daily;
        this.sensitive = sensitive;
    }

    /** 给记笔记的模型看的开着的事项：进行中和等待的，加上等你确认的；最多 {@link #OPEN_LIMIT} 项。 */
    public List<NotePrompt.Open> open() {
        List<MemoryItem> items = new ArrayList<>(working.open());
        items.addAll(working.pending());
        return items.stream().limit(OPEN_LIMIT).map(item -> new NotePrompt.Open(item.id(), cut(item.text(), OPEN_CHARS))).toList();
    }

    /**
     * 给记笔记的模型看的已有说法：偏好、事实、决定这三页上生效的和等你确认的，加上别的页上你认可过的决定，新的在前，最多 {@link #KNOWN_LIMIT} 条。
     * 模型据此不重复记，并写明新说法取代哪一条（{@link tantalum.memory.Replacing}）。
     */
    public List<NotePrompt.Known> known() {
        Map<String, String> kinds = Map.of(PREFERENCES.page(), "preference", ABOUT.page(), "fact", DECISIONS.page(), "decision");
        return store.ledger().items(item -> item.kind() == MemoryItem.Kind.CLAIM && item.state() == MemoryItem.State.CONFIRMED
                        && item.supersededBy() == null && item.page() != null
                        && (kinds.containsKey(item.page()) || item.status() == MemoryItem.Status.APPROVED)).stream()
                .sorted(java.util.Comparator.comparing(MemoryItem::created).reversed()).limit(KNOWN_LIMIT)
                .map(item -> new NotePrompt.Known(item.id(), kinds.getOrDefault(item.page(), "decision"), cut(item.text(), OPEN_CHARS),
                        item.status() == MemoryItem.Status.APPROVED, item.pendingConfirm())).toList();
    }

    static final int KNOWN_LIMIT = 40;
    static final int OPEN_LIMIT = 20;
    static final int OPEN_CHARS = 160;
    static final int SAID_CHARS = 240;
    static final int SAID_LINES = 3;
    /** 当天卡里你的原话这样开头。 */
    public static final String SAID = "> ";

    /** 这一轮你说的话，逐字、截短，每句一行；太短的（打招呼）和命中敏感信息的不记。 */
    private List<String> said(Turn turn) {
        List<String> lines = new ArrayList<>();
        for (RunSegment segment : turn.segments()) {
            if (lines.size() >= SAID_LINES) {
                break;
            }
            // 问答卡没答成（被拦、取消）时那一段写的是 “failed: …”，不是你的话
            boolean unanswered = segment.role() == RunSegment.Role.TOOL && segment.text().startsWith("failed:");
            if (!Quotes.userWords(segment) || unanswered || Quotes.normalize(segment.text()).length() < 4
                    || sensitive.detect(segment.text()).isPresent()) {
                continue;
            }
            lines.add(SAID + cut(segment.text(), SAID_CHARS));
        }
        return lines;
    }

    private static String cut(String text, int max) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /** 关掉一项开着的事：编号对得上、还开着才关。 */
    private boolean close(String id) {
        Optional<MemoryItem> found = working.ledger().get(id).filter(item -> WorkingMemory.KINDS.contains(item.kind())
                && item.state() == MemoryItem.State.CONFIRMED && item.supersededBy() == null
                && WorkingMemory.OPEN.contains(WorkingMemory.status(item)));
        return found.isPresent() && working.update(id, found.get().version(), null, Map.of(WorkingMemory.STATUS, "done"),
                MemoryItem.Actor.AGENT) instanceof WorkingMemory.Change.Done;
    }

    public Filed file(Turn turn, List<NotePrompt.Note> notes) {
        List<String> card = new ArrayList<>(said(turn));
        int kept = 0;
        int pending = 0;
        int dropped = 0;
        MemoryItem.Provenance provenance = new MemoryItem.Provenance(turn.session(), turn.run(), null, null);
        for (NotePrompt.Note note : notes) {
            Optional<String> hit = sensitive.detect(note.text(), note.quote());
            // “了结”的正文是事项的编号，不是一句话：只看原话在不在
            Quotes.Match match = hit.isPresent() ? Quotes.Match.NONE
                    : Quotes.match(note.kind() == NotePrompt.Kind.DONE ? null : note.text(), note.quote(), turn.segments(), turn.tainted());
            MemoryItem.Source source = match.source();
            if (source == null) {
                dropped++;
                continue;
            }
            if (note.kind() == NotePrompt.Kind.DONE) {
                if (close(note.text())) {
                    kept++;
                } else {
                    dropped++;
                }
                continue;
            }
            boolean said = match == Quotes.Match.SAID;
            if (!said && turn.tainted()) {
                if (note.kind() != NotePrompt.Kind.NOTE && promote(note, source, provenance, true)) {
                    pending++;
                } else {
                    dropped++;
                }
                continue;
            }
            if (said) {
                promote(note, source, provenance, false);
            }
            card.add("- " + note.text());
            kept++;
        }
        if (!card.isEmpty()) {
            daily.append(turn.day(), turn.session(), turn.title() == null ? "" : turn.title(), turn.run(), turn.heading(), String.join("\n", card));
        }
        if (dropped > 0) {
            LOG.info("memory note for run {}: dropped {} note(s) without a matching quote or with sensitive content", turn.run(), dropped);
        }
        return new Filed(kept, pending, dropped);
    }

    /** 进页面或工作记忆；“笔记”这种只进当天卡。写下了返回 true。 */
    private boolean promote(NotePrompt.Note note, MemoryItem.Source source, MemoryItem.Provenance provenance, boolean pendingConfirm) {
        Shelf shelf = switch (note.kind()) {
            case PREFERENCE -> PREFERENCES;
            case FACT -> ABOUT;
            case DECISION -> DECISIONS;
            default -> null;
        };
        if (shelf != null) {
            MemoryItem claim = MemoryItem.create(store.ledger().newId(), MemoryItem.Kind.CLAIM, source, MemoryItem.Actor.AGENT, note.text(),
                    store.ledger().clock().instant()).withQuote(note.quote()).withProvenance(provenance).withPendingConfirm(pendingConfirm);
            // 取代哪一条由模型写明（编号），这里只执行：你认可过的决定取代不了，标出冲突等你确认；别的在新说法生效时被它取代
            Optional<MemoryItem> old = tantalum.memory.Replacing.target(store.ledger(), note.replaces());
            if (old.isPresent()) {
                claim = tantalum.memory.Replacing.mark(claim, old.get());
            }
            Optional<MemoryItem> added = store.addClaim(shelf.page(), shelf.type(), shelf.description(), claim);
            added.ifPresent(written -> tantalum.memory.Replacing.apply(store.ledger(), written));
            return added.isPresent();
        }
        MemoryItem.Kind kind = switch (note.kind()) {
            case TASK -> MemoryItem.Kind.TASK;
            case COMMITMENT -> MemoryItem.Kind.COMMITMENT;
            case WAITING -> MemoryItem.Kind.WAITING;
            default -> null;
        };
        if (kind == null) {
            return false;
        }
        return working.add(kind, note.text(), Map.of(WorkingMemory.SESSION, provenance.session()), source, MemoryItem.Actor.AGENT,
                note.quote(), provenance, pendingConfirm, null).isPresent();
    }
}
