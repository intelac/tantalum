package tantalum.shell.journal;

import java.time.DateTimeException;
import java.time.Instant;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * 一行会话文件与 {@link Line} 互转（one-line-journal J1）：事件的字段（{@code seq}、{@code at}、{@code type}、{@code version}、
 * {@code run}、{@code payload}）同以前的事件行，后面加一个 {@code result}，内容同以前的结果行、不含序号。
 * 先让 memo 看到事件，再编码结果，所以结果里的“来自本事件”引用的是同一行的正文。
 */
public final class LineCodec {

    private final EventCodec events;
    private final RecordCodec records;

    public LineCodec(EventCodec events) {
        this.events = events;
        this.records = new RecordCodec(events);
    }

    public RecordCodec records() {
        return records;
    }

    /** 编码一行（不含换行符），并把这一行写过的内容记进 {@code memo}。 */
    public String encode(Line line, RecordMemo memo) {
        EventEnvelope envelope = line.envelope();
        String payload = events.encode(envelope.event());
        records.see(payload, memo);
        memo.seeEvent(envelope);
        String result = records.encodeTree(line.record(), memo).toString();
        String event = SessionFile.line(envelope, payload);
        // payload 和结果都已经是 JSON 文本，直接拼接，不解析第二次。
        return event.substring(0, event.length() - 1) + ",\"result\":" + result + "}";
    }

    /** 解析一行文本。不是 JSON 时抛 {@link IllegalArgumentException}。 */
    public JsonNode parse(String text) {
        try {
            return records.parse(text);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("not JSON: " + failure.getOriginalMessage(), failure);
        }
    }

    /** 解一行已经解析好的树。{@code memo} 是按文件顺序读到这里的读取方。坏行抛 {@link IllegalArgumentException}。 */
    public Line decode(JsonNode tree, Principal principal, SessionId session, RecordMemo memo) {
        if (!tree.path("seq").canConvertToLong() || !tree.path("at").isString() || !tree.path("type").isString()
                || !tree.path("version").canConvertToInt() || !tree.has("payload")) {
            throw new IllegalArgumentException("not an event record");
        }
        if (!tree.path("result").isObject()) {
            throw new IllegalArgumentException("event without a result");
        }
        long seq = tree.get("seq").asLong();
        EventEnvelope envelope;
        try {
            Event event = events.decode(tree.get("type").asString(), tree.get("version").asInt(), tree.get("payload"));
            RunId run = tree.path("run").isString() ? new RunId(tree.get("run").asString()) : null;
            envelope = new EventEnvelope(seq, Instant.parse(tree.get("at").asString()), principal, session, run, event);
        } catch (DateTimeException failure) {
            throw new IllegalArgumentException(failure.getMessage(), failure);
        }
        memo.see(tree.get("payload"));
        memo.seeEvent(envelope);
        RecordCodec.Decoded decoded = records.decode(tree.get("result"), seq, memo);
        return new Line(envelope, decoded.record(), decoded.fingerprints());
    }
}
