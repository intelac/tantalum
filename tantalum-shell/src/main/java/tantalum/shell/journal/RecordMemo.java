package tantalum.shell.journal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionContext;
import tools.jackson.databind.JsonNode;

/**
 * 一个会话文件里已经写过什么，编码结果行时用来省掉重复的正文：长字符串记哈希，再出现时写引用；记下上一次请求，
 * 下一次只写变化；跟着结果行记下会话的完整历史、上下文和运行，请求里的消息和工具写成它们的下标，上下文和运行只写变了的字段。
 * 写入方只要哈希（{@link #writer()}），读取方还要原文才能解开引用（{@link #reader()}）。
 * 不知道文件前面写过什么时（{@link #blind()}）按原样写，只是文件大一点，正确性不依赖它。
 */
public final class RecordMemo {

    /** UTF-8 至少这么长的字符串才值得写成引用（引用本身 66 个字符）。 */
    public static final int MIN_BYTES = 128;
    static final int PREFIX = 32;
    static final int HASH_BYTES = 16;
    /** 引用里哈希的十六进制长度。 */
    static final int HASH_HEX = HASH_BYTES * 2;

    private final Map<String, String> texts = new HashMap<>();
    /** 长字符串开头的 {@value #PREFIX} 个字符到它们的哈希：找“截掉中间一段”的出处用。 */
    private final Map<String, List<String>> byPrefix = new HashMap<>();
    /** 读取方：解开引用要原文。写入方也留着原文，用来找出新字符串是不是某个旧字符串掐头去尾的结果。 */
    private final boolean reading;
    /** 内容块（文字、思考、工具调用和结果……）整块的哈希：写入方只记哈希，读取方记解开后的 JSON。 */
    private final Set<String> knownBlocks;
    private final Map<String, JsonNode> blocks;
    /** 为 false 时不知道这个文件前面的结果行：历史和上下文不可信，不写下标和差异。 */
    private final boolean complete;
    /** 这个文件属于谁：一步的运行属于它时只写运行 ID。为 null 时不知道，写全。 */
    private Principal principal;
    private SessionId session;
    private final List<Message> messages = new ArrayList<>();
    /** 写入方用：历史里每条消息对象的下标。请求里没改过的消息就是历史里的同一个对象。 */
    private final Map<Message, Integer> positions = new IdentityHashMap<>();
    private final Map<RunId, RunState> runs = new HashMap<>();
    /** 事件行里模型回复的内容，按序号：这一步追加的助手消息就是它时，结果行只写“来自事件”。 */
    private final Map<Long, List<ContentBlock>> replies = new HashMap<>();
    private SessionContext context;
    private Prompt lastPrompt;
    /** 这个文件上一次组装上下文的完整载荷（{@link MatchedCodec}）；为 null 时下一次写全文。压缩后清空。 */
    private JsonNode matched;

    private RecordMemo(boolean keepTexts, boolean complete) {
        this.reading = keepTexts;
        this.knownBlocks = keepTexts ? null : new HashSet<>();
        this.blocks = keepTexts ? new HashMap<>() : null;
        this.complete = complete;
    }

    /** 新文件的写入方：前面什么都没有，所以知道全部。 */
    public static RecordMemo writer() {
        return new RecordMemo(false, true);
    }

    /** 同上，知道文件属于哪个会话。 */
    public static RecordMemo writer(Principal principal, SessionId session) {
        return new RecordMemo(false, true).owner(principal, session);
    }

    /** 不知道文件前面写过什么的写入方。 */
    public static RecordMemo blind() {
        return new RecordMemo(false, false);
    }

    /** 从文件开头读的读取方。 */
    public static RecordMemo reader() {
        return new RecordMemo(true, true);
    }

    /** 同上，知道文件属于哪个会话（来自文件头）。 */
    public static RecordMemo reader(Principal principal, SessionId session) {
        return new RecordMemo(true, true).owner(principal, session);
    }

    /** 不知道文件前面写过什么、但知道它属于哪个会话的写入方。 */
    public static RecordMemo blind(Principal principal, SessionId session) {
        return new RecordMemo(false, false).owner(principal, session);
    }

    private RecordMemo owner(Principal owner, SessionId ownSession) {
        this.principal = owner;
        this.session = ownSession;
        return this;
    }

    Principal principal() {
        return principal;
    }

    SessionId session() {
        return session;
    }

    /** 读完一个文件后交给写入方接着用。 */
    public RecordMemo toWriter() {
        RecordMemo writer = new RecordMemo(false, complete).owner(principal, session);
        writer.texts.putAll(texts);
        byPrefix.forEach((prefix, hashes) -> writer.byPrefix.put(prefix, new ArrayList<>(hashes)));
        writer.knownBlocks.addAll(blocks == null ? knownBlocks : blocks.keySet());
        writer.messages.addAll(messages);
        for (int i = 0; i < messages.size(); i++) {
            writer.positions.putIfAbsent(messages.get(i), i);
        }
        writer.runs.putAll(runs);
        writer.replies.putAll(replies);
        writer.context = context;
        writer.lastPrompt = lastPrompt;
        writer.matched = matched;
        return writer;
    }

    /** 收录一行事件里的长字符串（事件行由 {@link EventCodec} 写，不含引用）。内容块由 {@link RecordCodec#see} 收录。 */
    public void see(JsonNode node) {
        if (node.isString()) {
            remember(node.stringValue());
            return;
        }
        for (JsonNode child : node) {
            see(child);
        }
    }

    /** 同上，读文件时事件行已经解析成 {@link Json}。 */
    public void seeJson(Json node) {
        switch (node) {
            case Json.Str str -> remember(str.value());
            case Json.Arr arr -> arr.items().forEach(this::seeJson);
            case Json.Obj obj -> obj.fields().values().forEach(this::seeJson);
            default -> { }
        }
    }

    /** 事件行是模型回复：记下它的内容，等这一步的结果行用。 */
    public void seeEvent(EventEnvelope envelope) {
        if (envelope.event() instanceof ModelReplied reply) {
            replies.put(envelope.seq(), reply.content());
        }
    }

    /** 上一次组装上下文的完整载荷；不知道（新文件、不知道前文、刚压缩过）时为 null。 */
    JsonNode matched() {
        return complete ? matched : null;
    }

    void matched(JsonNode payload) {
        matched = payload;
    }

    List<ContentBlock> reply(long seq) {
        return replies.get(seq);
    }

    /** 这一步的结果行写完或读完，不再需要它的事件内容。 */
    void answered(long seq) {
        replies.remove(seq);
    }

    boolean remember(String text) {
        if (!isLong(text)) {
            return false;
        }
        String hash = hash(text);
        if (texts.putIfAbsent(hash, text) != null) {
            return false;
        }
        if (text.length() >= PREFIX) {
            byPrefix.computeIfAbsent(text.substring(0, PREFIX), ignored -> new ArrayList<>()).add(hash);
        }
        return true;
    }

    boolean knows(String hash) {
        return texts.containsKey(hash);
    }

    /** 和 {@code text} 开头相同的旧字符串的哈希。 */
    List<String> sameStart(String text) {
        return text.length() < PREFIX ? List.of() : byPrefix.getOrDefault(text.substring(0, PREFIX), List.of());
    }

    String text(String hash) {
        return texts.get(hash);
    }

    boolean knowsBlock(String hash) {
        return reading ? blocks.containsKey(hash) : knownBlocks.contains(hash);
    }

    void rememberBlock(String hash, JsonNode block) {
        if (reading) {
            blocks.putIfAbsent(hash, block.deepCopy());
        } else {
            knownBlocks.add(hash);
        }
    }

    JsonNode block(String hash) {
        return blocks.get(hash);
    }

    boolean complete() {
        return complete;
    }

    List<Message> messages() {
        return messages;
    }

    /** 历史追加了一条消息。 */
    void appended(Message message) {
        if (!reading) {
            positions.putIfAbsent(message, messages.size());
        }
        messages.add(message);
    }

    /** 这个消息对象在历史里的下标，不在时为 -1。 */
    int position(Message message) {
        Integer index = positions.get(message);
        return index == null ? -1 : index;
    }

    Map<RunId, RunState> runs() {
        return runs;
    }

    SessionContext context() {
        return context;
    }

    void context(SessionContext newContext) {
        context = newContext;
    }

    Prompt lastPrompt() {
        return lastPrompt;
    }

    void lastPrompt(Prompt prompt) {
        lastPrompt = prompt;
    }

    static boolean isLong(String text) {
        if (text.length() >= MIN_BYTES) {
            return true;
        }
        return text.length() * 3 >= MIN_BYTES && text.getBytes(StandardCharsets.UTF_8).length >= MIN_BYTES;
    }

    static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            // 前 128 位：在一个会话文件里足够区分，引用短一半
            return HexFormat.of().formatHex(digest, 0, HASH_BYTES);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
