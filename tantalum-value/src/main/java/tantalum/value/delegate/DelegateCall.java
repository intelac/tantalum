package tantalum.value.delegate;

import java.util.Map;
import tantalum.value.id.CommandId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/**
 * 内置工具 {@code delegate}（kernel-delegation）：内核认它的名字，不发 {@code RunTool}，把它变成一次交活；子会话交回的文字写成工具结果。
 * 做法照 {@link tantalum.value.question.AskUser}：这里只有纯函数，shell 里的 {@code DelegateTool} 只负责给模型的定义。
 * 参数：{@code task}（新活时必填）、{@code brief}（可选，子会话需要知道的背景）、{@code wait}（默认 true，等结果）、
 * {@code session}（可选：交给已有的子会话接着做）、{@code answer}（可选：回答 {@code session} 那个子会话问的问题）。
 */
public interface DelegateCall {

    String NAME = "delegate";

    /** 解析出的一次交活。{@code session} 为 null 是开新的子会话；{@code answer} 不为 null 是回答子会话的问题；{@code waits} 是等不等结果（参数 {@code wait}）。 */
    record Parsed(String task, String brief, boolean waits, SessionId session, String answer) {}

    /** 新子会话的编号：由委派号算出，回放时相同。 */
    static SessionId childOf(CommandId delegation) {
        return new SessionId("d-" + delegation.value());
    }

    /** 参数不合法时抛 {@link IllegalArgumentException}，信息直接给模型。 */
    static Parsed parse(Json arguments) {
        Map<String, Json> fields = arguments instanceof Json.Obj obj ? obj.fields() : null;
        if (fields == null) {
            throw new IllegalArgumentException("delegate needs an object with at least \"task\"");
        }
        String task = text(fields, "task");
        String brief = text(fields, "brief");
        String session = text(fields, "session");
        String answer = text(fields, "answer");
        boolean wait = true;
        Json rawWait = fields.get("wait");
        if (rawWait != null && !(rawWait instanceof Json.Null)) {
            if (!(rawWait instanceof Json.Bool bool)) {
                throw new IllegalArgumentException("\"wait\" must be true or false");
            }
            wait = bool.value();
        }
        if (answer != null && session == null) {
            throw new IllegalArgumentException("\"answer\" needs \"session\": the sub-conversation whose question you are answering");
        }
        if (task == null && answer == null) {
            throw new IllegalArgumentException("delegate needs a non-empty \"task\"");
        }
        return new Parsed(task, brief, wait, session == null ? null : new SessionId(session), answer);
    }

    /** 交给子会话的文字：新活写明它是被交代的、看不到父会话；接着做和回答问题只有内容。 */
    static String text(Parsed call, SessionId parent, boolean first) {
        if (call.answer() != null) {
            return call.answer();
        }
        String body = "Task:\n" + call.task() + (call.brief() == null ? "" : "\n\nWhat you need to know:\n" + call.brief());
        if (!first) {
            return body;
        }
        return "You are doing a task delegated by another conversation of the same user (" + parent.value()
                + "). It cannot see this conversation, so reply with a complete result; your final answer goes back to it.\n\n" + body;
    }

    private static String text(Map<String, Json> fields, String name) {
        Json value = fields.get(name);
        if (value == null || value instanceof Json.Null) {
            return null;
        }
        if (!(value instanceof Json.Str str)) {
            throw new IllegalArgumentException("\"" + name + "\" must be a string");
        }
        String stripped = str.value().strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
