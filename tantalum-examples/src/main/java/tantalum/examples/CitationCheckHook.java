package tantalum.examples;

import tantalum.kernel.hook.KernelHook;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;

/**
 * 引用核对：回答结束前要求核对引用。钩子不能做 I/O，所以返回 {@code NeedWork}，由 {@link CitationWork} 去读文件。
 * 看到工作结果就放行；核对不过时 {@code CitationWork} 会让运行回到模型。
 */
public final class CitationCheckHook implements KernelHook {

    @Override
    public String name() {
        return "citationCheck";
    }

    @Override
    public String description() {
        return "回答里引用的 文件:行号 必须真实存在，否则让模型改正。";
    }

    /** 核对本身失败（读不了文件、工作超时）不放行：一个“检查”钩子不能在检查没做成时当作通过。 */
    @Override
    public HookResult beforeRunEnd(KernelState state, HookContext context) {
        if (context.work() != null) {
            return context.work().failed() ? new Stop("citation check failed: " + context.work().result()) : Allow.PLAIN;
        }
        SessionState session = state.sessions().get(context.run().session());
        return new NeedWork(CitationWork.TYPE, new Json.Str(lastAnswer(session)));
    }

    private static String lastAnswer(SessionState session) {
        for (Message message : session.messages().reversed()) {
            if (message.role() == Role.ASSISTANT) {
                StringBuilder text = new StringBuilder();
                for (ContentBlock block : message.content()) {
                    if (block instanceof TextBlock plain) {
                        text.append(plain.text());
                    }
                }
                return text.toString();
            }
        }
        return "";
    }
}
