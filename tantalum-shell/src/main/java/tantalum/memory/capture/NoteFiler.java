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
 *   <li>原话在你的话里找到的（你亲口说的）直接生效：偏好、事实、决定写成页面上的说法，在做的事、答应的事、在等的事进工作记忆，都进当天卡；</li>
 *   <li>原话只在回答里的（推断）：这一轮没读过外部内容时只进当天卡；读过的话，偏好、事实、决定、事项记成“待你确认”，不进当天卡；</li>
 *   <li>原话哪里都找不到的、命中敏感信息检测的，丢掉。</li>
 * </ol>
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

    public Filed file(Turn turn, List<NotePrompt.Note> notes) {
        List<String> card = new ArrayList<>();
        int kept = 0;
        int pending = 0;
        int dropped = 0;
        MemoryItem.Provenance provenance = new MemoryItem.Provenance(turn.session(), turn.run(), null, null);
        for (NotePrompt.Note note : notes) {
            Optional<String> hit = sensitive.detect(note.text(), note.quote());
            MemoryItem.Source source = hit.isPresent() ? null : Quotes.find(note.quote(), turn.segments());
            if (source == null) {
                dropped++;
                continue;
            }
            boolean said = source == MemoryItem.Source.USER_SAID;
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
            List<MemoryItem> replaced = List.of();
            if (!pendingConfirm && note.kind() == NotePrompt.Kind.DECISION) {
                // 和你认可过的决定冲突：标出来、等你确认（memory-final-plan 第 9 节“写入”）
                Optional<MemoryItem> approved = tantalum.memory.recall.Overlap.approvedConflict(store.ledger(), note.text());
                if (approved.isPresent()) {
                    java.util.Map<String, String> attrs = new java.util.LinkedHashMap<>(claim.attrs());
                    attrs.put(MemoryStore.CONFLICTS_WITH, approved.get().id());
                    claim = claim.withPendingConfirm(true).withAttrs(attrs);
                }
            } else if (!pendingConfirm && note.kind() == NotePrompt.Kind.PREFERENCE) {
                // 你对自己偏好的新说法自动取代旧说法，不问你
                replaced = tantalum.memory.recall.Overlap.supersededPreferences(store.ledger(), shelf.page(), note.text());
                if (!replaced.isEmpty()) {
                    java.util.Map<String, String> attrs = new java.util.LinkedHashMap<>(claim.attrs());
                    attrs.put("supersedes", replaced.getFirst().id());
                    claim = claim.withAttrs(attrs);
                }
            }
            Optional<MemoryItem> added = store.addClaim(shelf.page(), shelf.type(), shelf.description(), claim);
            added.ifPresent(written -> {
                for (MemoryItem old : List.copyOf(tantalum.memory.recall.Overlap.supersededPreferences(store.ledger(), shelf.page(), written.text()))) {
                    if (!old.id().equals(written.id()) && written.attr("supersedes") != null) {
                        store.ledger().put(old.withSupersededBy(written.id()), old.version());
                    }
                }
            });
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
