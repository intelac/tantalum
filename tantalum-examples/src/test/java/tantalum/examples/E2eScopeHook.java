package tantalum.examples;

import java.util.concurrent.atomic.AtomicBoolean;
import tantalum.kernel.hook.HookMessages;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.model.Message;
import tantalum.value.model.TextBlock;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;

/**
 * 端到端测试“改了钩子规则，旧对话不变”（record-results 需求 11.4）用的访问范围钩子：打开 {@link #BLOCKING} 后，
 * 拦下最后一条用户消息带 {@value #KEYWORD} 的运行里的 {@code grep}。规则在运行中由 {@code /api/e2e/scope} 切换，
 * 相当于部署了新规则；其它测试的提问不带这个词，不受影响。
 */
final class E2eScopeHook implements KernelHook {

    static final String KEYWORD = "scope:";
    static final AtomicBoolean BLOCKING = new AtomicBoolean();

    @Override
    public String name() {
        return "e2eScope";
    }

    @Override
    public HookResult beforeToolCall(KernelState state, HookContext context) {
        return BLOCKING.get() && context.call().name().equals("grep") && asked(state, context)
                ? new Stop("grep is outside the allowed scope") : Allow.PLAIN;
    }

    private static boolean asked(KernelState state, HookContext context) {
        SessionState session = state.sessions().get(context.run().session());
        if (session == null) {
            return false;
        }
        for (Message message : session.messages().reversed()) {
            if (HookMessages.isUserText(message)) {
                return message.content().stream().anyMatch(block -> block instanceof TextBlock text && text.text().contains(KEYWORD));
            }
        }
        return false;
    }
}
