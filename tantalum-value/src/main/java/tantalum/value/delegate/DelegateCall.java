package tantalum.value.delegate;

import java.util.Map;
import tantalum.value.id.CommandId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/**
 * 内置工具 {@code delegate}：让另一个会话做一件事。内核认它的名字，不发 {@code RunTool}，把它变成一次送话
 * （{@link tantalum.value.event.SessionMessage}）。做法照 {@link tantalum.value.question.AskUser}：这里只有纯函数，
 * shell 里的 {@code DelegateTool} 只负责给模型的定义。
 * 参数：{@code task}（必填）、{@code brief}（可选，对方需要知道的背景）、{@code session}（可选：交给已有的会话，不给就开一个新的）、
 * {@code workspace}（可选：新会话开在哪个工作区，写那个工作区的目录；不给就和这个会话同一个目录。已有会话的目录定了不能改，
 * 所以不能和 {@code session} 一起给）、{@code wait}（默认 false：送出去就接着做，结果事后作为一条消息回来；true：这次调用等到对方
 * 回话，回话就是结果）。
 */
public interface DelegateCall {

    String NAME = "delegate";
    /** 不等的结果回来时给模型看的开头：写明不是用户说的。对话页、轨迹页据此认出它。 */
    String REPORT = "Answer from a conversation you handed work to (not from the user).\n";

    /** 解析出的一次调用。{@code session} 为 null 是开新会话；{@code workspace} 是新会话的目录（null 是沿用）；{@code waits} 是参数 {@code wait}。 */
    record Parsed(String task, String brief, SessionId session, String workspace, boolean waits) {}

    /** 新会话的编号：由那句话的编号算出，回放时相同。 */
    static SessionId sessionOf(CommandId message) {
        return new SessionId("s-" + message.value());
    }

    /**
     * 从这样算出的会话编号读出是哪个会话开的它：{@code s-<开它的会话>_<序号>-<下标>}。不是这个样子的为空。
     * 只给侧栏这类显示用；内核里没有父子关系。
     */
    static java.util.Optional<SessionId> openerOf(SessionId session) {
        String value = session.value();
        int cut = value.lastIndexOf('_');
        if (!value.startsWith("s-") || cut <= 2 || !value.substring(cut + 1).matches("\\d+-\\d+")) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new SessionId(value.substring(2, cut)));
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
        String workspace = text(fields, "workspace");
        if (session != null && workspace != null) {
            throw new IllegalArgumentException("give \"session\" or \"workspace\", not both: an existing conversation stays in its folder");
        }
        if (workspace != null && !(workspace.startsWith("/") || workspace.startsWith("\\\\") || workspace.matches("^[A-Za-z]:[\\\\/].*"))) {
            throw new IllegalArgumentException("\"workspace\" must be the workspace's absolute path, as listed in the workspaces section");
        }
        boolean wait = false;
        Json rawWait = fields.get("wait");
        if (rawWait != null && !(rawWait instanceof Json.Null)) {
            // 本机模型有时把布尔值写成字符串
            if (rawWait instanceof Json.Bool bool) {
                wait = bool.value();
            } else if (rawWait instanceof Json.Str str && (str.value().equalsIgnoreCase("true") || str.value().equalsIgnoreCase("false"))) {
                wait = str.value().equalsIgnoreCase("true");
            } else {
                throw new IllegalArgumentException("\"wait\" must be true or false");
            }
        }
        if (task == null) {
            throw new IllegalArgumentException("delegate needs a non-empty \"task\"");
        }
        return new Parsed(task, brief, session == null ? null : new SessionId(session), workspace, wait);
    }

    /** 送给对方的话：写明它来自另一个会话、不是用户在这里说的，对方看不到这边的对话。 */
    static String text(Parsed call, SessionId from) {
        return "This comes from another conversation of the same user (" + from.value() + "), not typed by the user here. It cannot "
                + "see this conversation, so end with a complete result; your final answer is sent back to it. Do the work yourself "
                + "here; do not hand it on.\n\nTask:\n"
                + call.task() + (call.brief() == null ? "" : "\n\nWhat you need to know:\n" + call.brief());
    }

    /**
     * 从送给对方的话里取出任务本身（去掉开场白和背景）；不是这样的话原样返回。会话的标题用它：另一个会话开出来的会话，
     * 标题是交给它的任务，不是开场白。
     */
    static String taskOf(String text) {
        int from = text == null ? -1 : text.indexOf("\n\nTask:\n");
        if (from < 0 || !text.startsWith("This comes from another conversation of the same user (")) {
            return text;
        }
        String task = text.substring(from + 8);
        int to = task.indexOf("\n\nWhat you need to know:\n");
        return to < 0 ? task : task.substring(0, to);
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
