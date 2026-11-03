package tantalum.examples;

import java.util.List;
import tantalum.kernel.hook.HookMessages;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.TextBlock;
import tantalum.value.state.Compacting;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;

/**
 * 钉住的上下文（{@code beforeCompact}）：用压缩计划和触发方式决定这次压缩能不能做。用户在消息里写上 {@code marker}，
 * 这条消息就不该被总结掉。参照 pi {@code session_before_compact} 的取消（{@code CA/src/core/agent-session.ts:3205-3221}）。
 * 结果：要被总结的头部里有钉住的用户消息，且是自动或手动压缩 → {@code Stop}；超长恢复放行（拒绝会让运行失败，宁可钉住的内容进摘要）；
 * 其余放行。自动压缩被拒只是这次不压，手动压缩被拒会告诉用户原因。
 */
public final class PinnedContextHook implements KernelHook {

    private final String marker;

    public PinnedContextHook(String marker) {
        if (marker == null || marker.isBlank()) {
            throw new IllegalArgumentException("marker must not be blank");
        }
        this.marker = marker;
    }

    @Override
    public String name() {
        return "pinnedContext";
    }

    @Override
    public HookResult beforeCompact(KernelState state, HookContext context) {
        if (context.plan() == null || context.trigger() == Compacting.Trigger.OVERFLOW) {
            return Allow.PLAIN;
        }
        SessionState session = state.sessions().get(context.run().session());
        if (session == null) {
            return Allow.PLAIN;
        }
        List<Message> view = session.view();
        List<Message> head = view.subList(0, Math.min(context.plan().headMessages(), view.size()));
        // 只认用户亲笔的消息：摘要或工具结果里出现标记不算，否则摘要引用过一次，以后每次压缩都会被拒。
        for (Message message : head) {
            if (HookMessages.isUserText(message) && text(message).contains(marker)) {
                return new Stop("a message pinned with " + marker + " would be summarized away");
            }
        }
        return Allow.PLAIN;
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
