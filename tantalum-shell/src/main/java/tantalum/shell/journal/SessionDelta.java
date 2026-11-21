package tantalum.shell.journal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.value.assignment.Assignments;
import tantalum.value.decision.SessionChange;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.Role;
import tantalum.value.state.Compaction;
import tantalum.value.state.HelperOf;
import tantalum.value.state.InboxItem;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionKind;
import tantalum.value.state.UsageAnchor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 结果行里会话的变化（从 {@code RecordCodec} 拆出）：追加的消息、变了的字段，以及只在变化时写的上下文。
 * 这一步追加的助手消息和事件里的回复相同时只写“来自事件”。
 */
final class SessionDelta {

    private static final TypeReference<List<InboxItem>> INBOX = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<List<tantalum.value.state.Withdrawal>> WITHDRAWN = new TypeReference<>() { };
    private static final TypeReference<List<tantalum.value.delivery.Outgoing>> OUTBOX = new TypeReference<>() { };
    private static final TypeReference<Map<String, Json>> NOTES = new TypeReference<>() { };

    private final JsonMapper mapper;
    private final Deltas deltas;

    SessionDelta(JsonMapper mapper, Deltas deltas) {
        this.mapper = mapper;
        this.deltas = deltas;
    }

    ObjectNode encode(SessionChange change, long seq, RecordMemo memo) {
        ObjectNode node = mapper.createObjectNode();
        // 文件头已经写明属于哪个会话：是它自己时不再写
        if (!change.id().equals(memo.session()) || !change.principal().equals(memo.principal())) {
            node.set("id", mapper.valueToTree(change.id()));
            node.set("principal", mapper.valueToTree(change.principal()));
        }
        // 这一步新建了它：建好不变的四样写在这一行里，之后的行不再写
        if (change.created()) {
            node.put("created", true);
            node.put("kind", change.kind().name());
            if (change.helper() != null) {
                node.set("helper", mapper.valueToTree(change.helper()));
            }
            if (change.dir() != null) {
                node.put("dir", change.dir());
            }
            if (change.offRecord()) {
                node.put("offRecord", true);
            }
        }
        if (!change.appended().isEmpty()) {
            List<ContentBlock> reply = memo.reply(seq);
            ArrayNode appended = node.putArray("appended");
            for (Message message : change.appended()) {
                ObjectNode written = mapper.valueToTree(message);
                if (reply != null && message.role() == Role.ASSISTANT && message.content().equals(reply)) {
                    written.remove("content");
                    written.put("fromEvent", true);
                }
                appended.add(written);
                memo.appended(message);
            }
        }
        // 压缩完成时去掉了窗口之前的消息：记下新的起点（之前的原话照旧在这个文件的前面）
        changed(node, "windowStart", change.windowStart());
        changed(node, "activeRun", change.activeRun());
        changed(node, "lastRun", change.lastRun());
        // 收件队列：到了、还没被模型看到的输入。变了就写整条队列，通常只有零到几条
        changed(node, "inbox", change.inbox());
        if (change.context() != null) {
            node.set("context", context(change.context().value(), memo));
        }
        changed(node, "compaction", change.compaction());
        changed(node, "anchor", change.anchor());
        changed(node, "omittedImages", change.omittedImages());
        changed(node, "toolsSeen", change.toolsSeen());
        changed(node, "notes", change.notes());
        changed(node, "model", change.model());
        // 交代这一块（放出去还没回来的、没人说话时的两个计数）：没变的会话不写
        changed(node, "assignments", change.assignments());
        // 撤下的文字：历史不改，只多记这一份；没撤下过的会话不写
        changed(node, "withdrawn", change.withdrawn());
        changed(node, "outbox", change.outbox());
        return node;
    }

    SessionChange decode(JsonNode node, long seq, RecordMemo memo) {
        List<Message> appended = new ArrayList<>();
        for (JsonNode item : node.path("appended")) {
            Message message;
            if (item.path("fromEvent").asBoolean(false)) {
                List<ContentBlock> reply = memo.reply(seq);
                if (reply == null) {
                    throw new IllegalArgumentException("message from an event that is not a model reply of this step");
                }
                message = new Message(Role.valueOf(item.required("role").asString()), reply, item.path("interrupted").asBoolean(false));
            } else {
                message = mapper.treeToValue(item, Message.class);
            }
            appended.add(message);
            memo.appended(message);
        }
        if (!node.has("id") && memo.session() == null) {
            throw new IllegalArgumentException("a session change without its id needs the file's session");
        }
        boolean created = node.path("created").asBoolean(false);
        return new SessionChange(node.has("id") ? mapper.treeToValue(node.get("id"), SessionId.class) : memo.session(),
                node.has("principal") ? mapper.treeToValue(node.get("principal"), Principal.class) : memo.principal(),
                created, created ? SessionKind.valueOf(node.required("kind").asString()) : null,
                created && node.has("helper") ? mapper.treeToValue(node.get("helper"), HelperOf.class) : null,
                created && node.has("dir") ? node.get("dir").asString() : null, created && node.path("offRecord").asBoolean(false), appended,
                changed(node, "windowStart", Integer.class),
                changed(node, "activeRun", RunId.class), changed(node, "lastRun", RunId.class),
                changedList(node, "inbox", INBOX),
                node.has("context") ? new SessionChange.Changed<>(context(node.get("context"), memo)) : null,
                changed(node, "compaction", Compaction.class), changed(node, "anchor", UsageAnchor.class),
                changedList(node, "omittedImages", STRINGS), changedList(node, "toolsSeen", STRINGS), notes(node),
                changed(node, "model", ModelChoice.class), changed(node, "assignments", Assignments.class),
                changedList(node, "withdrawn", WITHDRAWN), changedList(node, "outbox", OUTBOX));
    }

    /** 插件工作记在会话里的东西：工作类型 → 对象。 */
    private SessionChange.Changed<Map<String, Json.Obj>> notes(JsonNode node) {
        if (!node.has("notes")) {
            return null;
        }
        JsonNode value = node.get("notes");
        if (value.isNull()) {
            return new SessionChange.Changed<>(null);
        }
        Map<String, Json.Obj> notes = new LinkedHashMap<>();
        mapper.convertValue(value, NOTES).forEach((type, note) -> {
            if (!(note instanceof Json.Obj obj)) {
                throw new IllegalArgumentException("note of " + type + " is not an object");
            }
            notes.put(type, obj);
        });
        return new SessionChange.Changed<>(notes);
    }

    /** 上下文只在会话开始时带上 system 和全部工具，之后每次提问只换几个字段：知道上一份时只写变了的字段。 */
    private JsonNode context(SessionContext context, RecordMemo memo) {
        SessionContext last = memo.complete() ? memo.context() : null;
        memo.context(context);
        if (context == null) {
            return mapper.nullNode();
        }
        ObjectNode full = mapper.valueToTree(context);
        return last == null ? full : deltas.delta(full, mapper.valueToTree(last), null);
    }

    private SessionContext context(JsonNode node, RecordMemo memo) {
        if (node.isNull()) {
            memo.context(null);
            return null;
        }
        SessionContext context = node.path("delta").asBoolean(false)
                ? mapper.treeToValue(deltas.merge(node, memo.context(), "context"), SessionContext.class)
                : mapper.treeToValue(node, SessionContext.class);
        memo.context(context);
        return context;
    }

    /** 变了才写；变成 null 写 JSON null。 */
    private void changed(ObjectNode node, String name, SessionChange.Changed<?> change) {
        if (change != null) {
            node.set(name, change.value() == null ? mapper.nullNode() : mapper.valueToTree(change.value()));
        }
    }

    private <T> SessionChange.Changed<T> changed(JsonNode node, String name, Class<T> type) {
        if (!node.has(name)) {
            return null;
        }
        JsonNode value = node.get(name);
        return new SessionChange.Changed<>(value.isNull() ? null : mapper.treeToValue(value, type));
    }

    private <T> SessionChange.Changed<List<T>> changedList(JsonNode node, String name, TypeReference<List<T>> type) {
        if (!node.has(name)) {
            return null;
        }
        JsonNode value = node.get(name);
        return new SessionChange.Changed<>(value.isNull() ? null : mapper.convertValue(value, type));
    }
}
