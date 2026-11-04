package tantalum.shell.journal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tantalum.value.id.RunId;
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
    public static final int MIN_BYTES = 256;

    private final Set<String> known;
    private final Map<String, String> texts;
    /** 为 false 时不知道这个文件前面的结果行：历史和上下文不可信，不写下标和差异。 */
    private final boolean complete;
    private final List<Message> messages = new ArrayList<>();
    private final Map<RunId, RunState> runs = new HashMap<>();
    private SessionContext context;
    private Prompt lastPrompt;

    private RecordMemo(boolean keepTexts, boolean complete) {
        this.known = keepTexts ? null : new HashSet<>();
        this.texts = keepTexts ? new HashMap<>() : null;
        this.complete = complete;
    }

    /** 新文件的写入方：前面什么都没有，所以知道全部。 */
    public static RecordMemo writer() {
        return new RecordMemo(false, true);
    }

    /** 不知道文件前面写过什么的写入方。 */
    public static RecordMemo blind() {
        return new RecordMemo(false, false);
    }

    /** 从文件开头读的读取方。 */
    public static RecordMemo reader() {
        return new RecordMemo(true, true);
    }

    /** 读完一个文件后交给写入方接着用。 */
    public RecordMemo toWriter() {
        RecordMemo writer = new RecordMemo(false, complete);
        writer.known.addAll(texts == null ? known : texts.keySet());
        writer.messages.addAll(messages);
        writer.runs.putAll(runs);
        writer.context = context;
        writer.lastPrompt = lastPrompt;
        return writer;
    }

    /** 收录一行事件里的长字符串（事件行由 {@link EventCodec} 写，不含引用）。 */
    public void see(JsonNode node) {
        if (node.isString()) {
            remember(node.stringValue());
            return;
        }
        for (JsonNode child : node) {
            see(child);
        }
    }

    boolean remember(String text) {
        if (!isLong(text)) {
            return false;
        }
        String hash = hash(text);
        if (texts != null) {
            return texts.putIfAbsent(hash, text) == null;
        }
        return known.add(hash);
    }

    boolean knows(String hash) {
        return texts != null ? texts.containsKey(hash) : known.contains(hash);
    }

    String text(String hash) {
        return texts.get(hash);
    }

    boolean complete() {
        return complete;
    }

    List<Message> messages() {
        return messages;
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
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
