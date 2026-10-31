package tantalum.shell.sse;

/** 一条 SSE 消息。瞬态分片没有 {@code id}，不能补读。 */
public record SseMessage(String id, String event, String data) {}
