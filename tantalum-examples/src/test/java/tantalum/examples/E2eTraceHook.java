package tantalum.examples;

import java.util.List;
import tantalum.kernel.hook.HookMessages;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.PromptPatch;
import tantalum.value.hook.Stop;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;

/**
 * 端到端测试轨迹页用的钩子，只对最后一条用户消息带 {@value #KEYWORD} 的运行起作用，其它测试不受影响：
 * 模型调用前加一段 system 补丁；拦下读 {@code secret} 的工具调用；结束前要一轮 {@link CitationWork} 核对引用。
 */
final class E2eTraceHook implements KernelHook {

    static final String KEYWORD = "trace:";
    static final String PATCH = "Trace e2e: keep answers short.";

    @Override
    public String name() {
        return "e2eTrace";
    }

    @Override
    public HookResult beforeModelCall(KernelState state, HookContext context) {
        return applies(state, context) ? new Allow(new PromptPatch(PATCH, List.of())) : Allow.PLAIN;
    }

    @Override
    public HookResult beforeToolCall(KernelState state, HookContext context) {
        boolean secret = context.call().name().equals("read_file") && context.call().rawArguments().contains("secret");
        return applies(state, context) && secret ? new Stop("secret files are off limits") : Allow.PLAIN;
    }

    @Override
    public HookResult beforeRunEnd(KernelState state, HookContext context) {
        if (!applies(state, context)) {
            return Allow.PLAIN;
        }
        if (context.work() != null) {
            return context.work().failed() ? new Stop("citation check failed: " + context.work().result()) : Allow.PLAIN;
        }
        return new NeedWork(CitationWork.TYPE, new Json.Str(lastAnswer(state.sessions().get(context.run().session()))));
    }

    private static boolean applies(KernelState state, HookContext context) {
        SessionState session = state.sessions().get(context.run().session());
        if (session == null) {
            return false;
        }
        for (Message message : session.messages().reversed()) {
            if (HookMessages.isUserText(message)) {
                return text(message).contains(KEYWORD);
            }
        }
        return false;
    }

    private static String lastAnswer(SessionState session) {
        for (Message message : session.messages().reversed()) {
            if (message.role() == Role.ASSISTANT) {
                return text(message);
            }
        }
        return "";
    }

    private static String text(Message message) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
            if (block instanceof TextBlock plain) {
                text.append(plain.text());
            }
        }
        return text.toString();
    }
}
