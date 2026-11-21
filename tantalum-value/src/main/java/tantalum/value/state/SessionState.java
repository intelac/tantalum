package tantalum.value.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tantalum.value.assignment.Assignments;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolSpec;

/** 一个会话。{@code kind} 是它的种类，{@code helper} 只有帮手会话有；两样和 {@code dir}、{@code offRecord} 一样建会话时定下，之后不变。
 * {@code messages} 是完整历史，只追加；模型看到的是 {@link #view()}。{@code activeRun} 没有运行时为空；
 * {@code lastRun} 是最近一次开始的运行，运行结束后仍保留，用来判断迟到的结果是否还能进历史。
 * {@code inbox} 是收件队列：到了、还没被模型看到的输入，按到的先后。一轮怎么结束它们都还在。
 * {@code compaction} 是最近一次压缩的替换段，{@code anchor} 是上次回复的实测用量，两者都可为空。
 * {@code omittedImages} 是因超出请求的图片预算而省略过的附件 ID，只增不减，之后的请求里一直省略。
 * {@code toolsSeen} 是这个会话里开着过的工具名，按第一次出现的顺序，只增不减，压缩也不清空：压缩后据此告诉模型哪些工具
 * 已经不能用了。
 * {@code notes} 是插件工作记在会话里的东西，按工作类型分，每份是一个对象（{@link tantalum.value.event.WorkDone#note()} 按字段并进来），
 * 只来自事件，钩子读它就和读历史一样是纯的；压缩不清空。例如工具审批的“这个会话里一直允许”。
 * {@code model} 是会话现在的模型选择，每次提问组装上下文时换成这次用的，压缩不清空。
 * {@code dir} 是会话的工作目录（用户电脑上的绝对路径），没选为 null。{@code offRecord} 为真是“不进记忆”的会话。
 * {@code assignments} 是和交代有关的一块（交代出去还没回来的、无人说话时的计数），没有时是 {@link Assignments#NONE}。
 * {@code withdrawn} 是撤下的文字（{@link Withdrawal}），只增不减：历史照旧只追加、一个字不改，{@link #view()} 给模型看的时候把它们换掉。 */
public record SessionState(SessionId id, Principal principal, SessionKind kind, HelperOf helper, List<Message> messages,
                           RunId activeRun, RunId lastRun, List<InboxItem> inbox, SessionContext context,
                           Compaction compaction, UsageAnchor anchor, List<String> omittedImages, List<String> toolsSeen,
                           Map<String, Json.Obj> notes, ModelChoice model, String dir, boolean offRecord, Assignments assignments,
                           List<Withdrawal> withdrawn) {

    public SessionState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(kind, "kind");
        if ((kind == SessionKind.HELPER) != (helper != null)) {
            throw new IllegalArgumentException("exactly a helper session says whose helper it is");
        }
        messages = List.copyOf(messages);
        inbox = inbox == null ? List.of() : List.copyOf(inbox);
        omittedImages = omittedImages == null ? List.of() : List.copyOf(omittedImages);
        toolsSeen = toolsSeen == null ? List.of() : List.copyOf(toolsSeen);
        notes = notes == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(notes));
        assignments = assignments == null ? Assignments.NONE : assignments;
        withdrawn = withdrawn == null ? List.of() : List.copyOf(withdrawn);
        if (compaction != null && compaction.tailFrom() > messages.size()) {
            throw new IllegalArgumentException("tailFrom " + compaction.tailFrom() + " beyond " + messages.size() + " messages");
        }
    }

    /** 一个刚建好的会话。帮手会话的 {@code helper} 不能为 null，别的种类必须为 null。 */
    public static SessionState start(SessionId id, Principal principal, SessionKind kind, HelperOf helper, String dir, boolean offRecord) {
        return new SessionState(id, principal, kind, helper, List.of(), null, null, List.of(), null, null, null, List.of(), List.of(), Map.of(),
                null, dir, offRecord, Assignments.NONE, List.of());
    }

    /** 模型看到的消息：压缩过就是替换段加上之后追加的，否则就是全部历史；撤下的文字（{@link #withdrawn}）已经换掉。 */
    public List<Message> view() {
        if (compaction == null && withdrawn.isEmpty()) {
            return messages;
        }
        int from = compaction == null ? 0 : compaction.tailFrom();
        List<Message> view = new ArrayList<>(compaction == null ? List.of() : compaction.replacement());
        for (int i = from; i < messages.size(); i++) {
            view.add(said(i));
        }
        return List.copyOf(view);
    }

    /** 历史里的第 {@code index} 条，照模型看到的样子：在它之后撤下的文字已经换掉。没有撤下过时就是原来那条。 */
    public Message said(int index) {
        Message message = messages.get(index);
        if (withdrawn.isEmpty()) {
            return message;
        }
        List<tantalum.value.event.TextWithdrawn.Swap> swaps = withdrawn.stream().filter(each -> index < each.before()).map(Withdrawal::swap).toList();
        return swaps.isEmpty() ? message : new tantalum.value.model.TextSwap(swaps).message(message);
    }

    /**
     * 撤下文字：{@code more} 记进 {@link #withdrawn}（历史不动）；收件队列、压缩的替换段、冻结的上下文不是只追加的，
     * 调用方把换过的直接交进来。
     */
    public SessionState withdrawing(List<Withdrawal> more, List<InboxItem> newInbox, Compaction newCompaction, SessionContext newContext) {
        List<Withdrawal> all = new ArrayList<>(withdrawn);
        all.addAll(more);
        return new SessionState(id, principal, kind, helper, messages, activeRun, lastRun, newInbox, newContext, newCompaction, anchor, omittedImages, toolsSeen, notes, model, dir, offRecord, assignments, all);
    }

    public SessionState append(Message message) {
        List<Message> updated = new ArrayList<>(messages);
        updated.add(message);
        return new SessionState(id, principal, kind, helper, updated, activeRun, lastRun, inbox, context, compaction, anchor, omittedImages, toolsSeen, notes, model, dir, offRecord, assignments, withdrawn);
    }

    /** 运行开始：同时记为 {@code lastRun}。 */
    public SessionState startRun(RunId run) {
        Objects.requireNonNull(run, "run");
        return new SessionState(id, principal, kind, helper, messages, run, run, inbox, context, compaction, anchor, omittedImages, toolsSeen, notes, model, dir, offRecord, assignments, withdrawn);
    }

    /** 运行结束：只清 {@code activeRun}，{@code lastRun} 留着判断迟到的结果。 */
    public SessionState endRun() {
        return new SessionState(id, principal, kind, helper, messages, null, lastRun, inbox, context, compaction, anchor, omittedImages, toolsSeen, notes, model, dir, offRecord, assignments, withdrawn);
    }

    /** 换整条收件队列。 */
    public SessionState withInbox(List<InboxItem> newInbox) {
        return new SessionState(id, principal, kind, helper, messages, activeRun, lastRun, newInbox, context, compaction, anchor, omittedImages, toolsSeen, notes, model, dir, offRecord, assignments, withdrawn);
    }

    /** 队尾加一条。 */
    public SessionState enqueue(InboxItem item) {
        List<InboxItem> grown = new ArrayList<>(inbox);
        grown.add(item);
        return withInbox(grown);
    }

    public SessionState withContext(SessionContext newContext) {
        return new SessionState(id, principal, kind, helper, messages, activeRun, lastRun, inbox, newContext, compaction, anchor, omittedImages, toolsSeen, notes, model, dir, offRecord, assignments, withdrawn);
    }

    /** 压缩成功：换替换段，锚点作废。 */
    public SessionState compacted(Compaction newCompaction) {
        return new SessionState(id, principal, kind, helper, messages, activeRun, lastRun, inbox, context, newCompaction, null, omittedImages, toolsSeen, notes, model, dir, offRecord, assignments, withdrawn);
    }

    public SessionState withAnchor(UsageAnchor newAnchor) {
        return new SessionState(id, principal, kind, helper, messages, activeRun, lastRun, inbox, context, compaction, newAnchor, omittedImages, toolsSeen, notes, model, dir, offRecord, assignments, withdrawn);
    }

    public SessionState withOmittedImages(List<String> newOmittedImages) {
        return new SessionState(id, principal, kind, helper, messages, activeRun, lastRun, inbox, context, compaction, anchor, newOmittedImages, toolsSeen, notes, model, dir, offRecord, assignments, withdrawn);
    }

    public SessionState withToolsSeen(List<String> newToolsSeen) {
        return new SessionState(id, principal, kind, helper, messages, activeRun, lastRun, inbox, context, compaction, anchor, omittedImages, newToolsSeen, notes, model, dir, offRecord, assignments, withdrawn);
    }

    /** 换会话的模型选择。 */
    public SessionState withModel(ModelChoice newModel) {
        return new SessionState(id, principal, kind, helper, messages, activeRun, lastRun, inbox, context, compaction, anchor, omittedImages, toolsSeen, notes, newModel, dir, offRecord, assignments, withdrawn);
    }

    /** 这个工作类型记在会话里的东西，没有时是空对象。 */
    public Json.Obj note(String workType) {
        Json.Obj note = notes.get(workType);
        return note == null ? new Json.Obj(Map.of()) : note;
    }

    /** 把 {@code fields} 按字段并进这个工作类型的那一份；值是 {@link Json.Null} 的字段删掉。字段按名字排序，结果和顺序无关。 */
    public SessionState withNote(String workType, Json.Obj fields) {
        Map<String, Json> merged = new TreeMap<>(note(workType).fields());
        fields.fields().forEach((name, value) -> {
            if (value instanceof Json.Null) {
                merged.remove(name);
            } else {
                merged.put(name, value);
            }
        });
        Map<String, Json.Obj> updated = new TreeMap<>(notes);
        if (merged.isEmpty()) {
            updated.remove(workType);
        } else {
            updated.put(workType, new Json.Obj(Collections.unmodifiableMap(new LinkedHashMap<>(merged))));
        }
        return new SessionState(id, principal, kind, helper, messages, activeRun, lastRun, inbox, context, compaction, anchor, omittedImages, toolsSeen, updated, model, dir, offRecord, assignments, withdrawn);
    }

    /**
     * 这个会话有没有工具变化：一开始就有关着的工具，或者模型看到的历史里有工具变化消息。有的话，不支持会话中途工具变化的
     * Claude 模型不能选：它们看到的历史和支持的模型不一样，换回来时思考签名会失效。
     */
    public boolean toolChangesInHistory() {
        if (context != null && context.initialTools() != null
                && !context.initialTools().containsAll(context.tools().stream().map(ToolSpec::name).toList())) {
            return true;
        }
        return view().stream().anyMatch(message -> message.content().stream().anyMatch(ToolChangeBlock.class::isInstance));
    }

    /** 该运行是否仍是会话历史的末尾：它正在进行，或它结束后没有开始过新运行。 */
    public boolean isLatestRun(RunId run) {
        return run != null && run.equals(lastRun);
    }

    /**
     * 压缩刚换过视图、上下文还没重新冻结：压缩采用后 {@code context} 清空而 {@code compaction} 有值，直到下一次组装上下文。
     * 那次组装之后的模型调用不再判断自动压缩（压缩规则 3.1），由调用方在冻结之前看这一项。
     */
    public boolean justCompacted() {
        return context() == null && compaction() != null;
    }

    public SessionState withAssignments(Assignments newAssignments) {
        return new SessionState(id, principal, kind, helper, messages, activeRun, lastRun, inbox, context, compaction, anchor, omittedImages, toolsSeen, notes, model, dir, offRecord, newAssignments, withdrawn);
    }
}
