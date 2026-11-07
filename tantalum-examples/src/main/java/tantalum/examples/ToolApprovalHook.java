package tantalum.examples;

import java.util.List;
import java.util.Map;
import java.util.Set;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.hook.Allow;
import tantalum.value.hook.Ask;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.json.Json;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.question.Answer;
import tantalum.value.question.Question;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;
import tantalum.value.text.LocalText;

/**
 * 工具审批（{@code beforeToolCall} + {@code onAnswer}）：调用列出的工具前在对话页上问用户“允许调用这个工具吗”，三个选项：
 * 允许这一次、这个会话里一直允许、拒绝。问了就等（waiting-state）：人几小时后回答也接着跑，服务器重启问题还在。
 *
 * <p>“这个会话里一直允许”：放行时带一条会话记录（{@code Allow.withNote("approval", {"工具名": true})}），内核记进
 * {@code SessionState.note("approval")}，随结果行进 journal；之后这个会话里看到就不再问，别的会话照样问。
 * 允许名单只来自记录，钩子仍是纯函数，恢复和重放结果相同。参照：DSH 的工具审批只有一次性的“允许这一次”和整个会话的策略开关
 * （{@code packages/interaction/user-approval/README.md:165}）；pi 的 {@code examples/extensions/permission-gate.ts:25} 每次都问
 * “Yes / No”，什么都不记。拒绝时告诉模型不要换个工具绕过去（同 DSH {@code application-access.ts:299-301}）。
 *
 * <p>结果：列出的工具 → {@code Ask}；这个会话里一直允许的 → 放行；同意 → 放行；拒绝、期限到没人答 → {@code Stop}，只拦这一个调用。
 * 默认关（策略插件），开着时出错按拦截处理。
 */
public final class ToolApprovalHook implements KernelHook {

    /** 会话记录的键，也是问题 id 的前缀。 */
    public static final String NOTE = "approval";
    static final String QUESTION_PREFIX = "approval-";
    /** 三个选项的 id。 */
    static final String ONCE = "once";
    static final String SESSION = "session";
    static final String DENY = "deny";
    private static final int SHOWN_ARGUMENT_CHARS = 2000;

    private final Set<String> tools;

    public ToolApprovalHook(Set<String> tools) {
        this.tools = Set.copyOf(tools);
    }

    @Override
    public String name() {
        return "toolApproval";
    }

    @Override
    public LocalText description() {
        // 例子插件示范带三种语言的写法：控制台按看的人的语言选，没有就用英文（console-i18n I10）
        return LocalText.en("Before a listed tool is called, asks you on the conversation page and waits as long as needed: allow once, always allow in this session, or deny.")
                .zh("调用列出的工具前先在对话页上问你，等多久都行：允许这一次、这个会话里一直允许，或者拒绝。")
                .ja("リストにあるツールを呼び出す前に会話ページで確認し、回答まで待ちます。今回だけ許可、このセッションでは常に許可、または拒否を選べます。");
    }

    /** 策略插件：默认关，用户在插件页打开（管理员要对所有人强制开启时写进 {@code tantalum.hooks.required}）；开着时出错按拦截处理。 */
    @Override
    public boolean defaultEnabled() {
        return false;
    }

    @Override
    public boolean failClosed() {
        return true;
    }

    @Override
    public HookResult beforeToolCall(KernelState state, HookContext context) {
        ToolCallBlock call = context.call();
        if (call == null || !tools.contains(call.name()) || allowedInSession(state, context, call.name())) {
            return Allow.PLAIN;
        }
        return new Ask(question(call));
    }

    /** 这个会话里用户选过“一直允许”的工具。 */
    private static boolean allowedInSession(KernelState state, HookContext context, String tool) {
        SessionState session = state.sessions().get(context.run().session());
        return session != null && session.note(NOTE).fields().get(tool) instanceof Json.Bool allowed && allowed.value();
    }

    /** 同一个调用再问就是同一个问题；说明是参数的 JSON 原文，太长截断。 */
    static Question question(ToolCallBlock call) {
        String tool = call.name();
        return new Question(QUESTION_PREFIX + call.id(),
                LocalText.en("Allow calling " + tool + "?").zh("允许调用 " + tool + " 吗？").ja(tool + " の呼び出しを許可しますか？"),
                LocalText.of(shown(call.arguments() == null ? new Json.Null() : call.arguments())), List.of(
                new Question.Choice(ONCE, LocalText.en("Allow once").zh("允许这一次").ja("今回だけ許可")),
                new Question.Choice(SESSION, LocalText.en("Always allow in this session").zh("这个会话里一直允许").ja("このセッションでは常に許可")),
                new Question.Choice(DENY, LocalText.en("Deny").zh("拒绝").ja("拒否"))));
    }

    /** 每个问题的回答都会来这里，只看自己的。工具名从问题的说明之外拿不到，所以记在会话里时用调用的工具名：从上下文的调用里取。 */
    @Override
    public HookResult onAnswer(KernelState state, HookContext context) {
        Question question = context.question();
        if (question == null || !question.id().startsWith(QUESTION_PREFIX)) {
            return Allow.PLAIN;
        }
        Answer answer = context.answer();
        String tool = context.call() == null ? "this tool" : context.call().name();
        if (answer == null) {
            return new Stop("no approval decision for " + tool + " before the deadline");
        }
        return switch (answer.choice()) {
            case ONCE -> Allow.PLAIN;
            case SESSION -> Allow.PLAIN.withNote(NOTE, new Json.Obj(Map.of(tool, new Json.Bool(true))));
            default -> new Stop("the user denied this call to " + tool
                    + ". Do not retry it or use another tool to get around this decision.");
        };
    }

    /** 页面上显示的参数：JSON 原文，太长时截断，不在代理对中间切开。 */
    private static String shown(Json arguments) {
        String text = write(arguments);
        if (text.length() <= SHOWN_ARGUMENT_CHARS) {
            return text;
        }
        int cut = Character.isHighSurrogate(text.charAt(SHOWN_ARGUMENT_CHARS - 1)) ? SHOWN_ARGUMENT_CHARS - 1 : SHOWN_ARGUMENT_CHARS;
        return text.substring(0, cut) + "…";
    }

    /** 把 {@link Json} 写成 JSON 文本：插件只用公开的插件 API，不用 shell 内部的 JSON 工具。 */
    static String write(Json value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Json value, StringBuilder out) {
        switch (value) {
            case Json.Null ignored -> out.append("null");
            case Json.Bool bool -> out.append(bool.value());
            case Json.Num number -> out.append(number.value().toString());
            case Json.Str text -> quote(text.value(), out);
            case Json.Arr array -> {
                out.append('[');
                for (int i = 0; i < array.items().size(); i++) {
                    out.append(i == 0 ? "" : ",");
                    write(array.items().get(i), out);
                }
                out.append(']');
            }
            case Json.Obj obj -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, Json> field : obj.fields().entrySet()) {
                    out.append(first ? "" : ",");
                    first = false;
                    quote(field.getKey(), out);
                    out.append(':');
                    write(field.getValue(), out);
                }
                out.append('}');
            }
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
