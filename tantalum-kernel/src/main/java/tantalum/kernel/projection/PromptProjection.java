package tantalum.kernel.projection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tantalum.value.hook.PromptPatch;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.Role;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionState;

/** 从状态投影出一次模型调用的 {@code Prompt}。消息取会话的视图（压缩过就是替换段加新消息）。回放时重新计算即可还原，不单独落盘。 */
public final class PromptProjection {

    private PromptProjection() {}

    /**
     * 会话冻结的 system 和工具，后面接上钩子补丁；普通调用和摘要调用都从这里取，两者前缀相同。会话开始时没开的工具标为 deferred：
     * 定义照发、模型看不见，等历史里的工具变化把它加进来（core-tools C14）。
     */
    public static Prompt project(SessionState session, PromptPatch patch) {
        SessionContext context = session.context();
        List<ToolSpec> tools = new ArrayList<>();
        for (ToolSpec tool : context.tools()) {
            tools.add(tool.deferred(context.initialTools() != null && !context.initialTools().contains(tool.name())));
        }
        tools.addAll(patch.addTools());
        return new Prompt(context.system() + patch.systemAppend(), tools, session.view());
    }

    /**
     * 工具变化的系统消息要紧跟在用户消息后面，后面要么是模型的回复、要么是最后一条（Claude 的规则）。正常情况下它就在提问和回复
     * 之间；一次提问没等到回复就失败、下一次提问又来时，它后面跟的是用户消息。这时把它挪到下一处合法的位置，和那里的变化合并成
     * 净变化。被挪的那条后面没有回复，没有任何思考签名绑定过它，挪动不影响校验。纯函数，回放结果相同。
     */
    public static List<Message> placeToolChanges(List<Message> messages) {
        List<Message> out = new ArrayList<>();
        Set<String> added = new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (isToolChange(message)) {
                for (var block : message.content()) {
                    ToolChangeBlock change = (ToolChangeBlock) block;
                    change.added().forEach(name -> { if (!removed.remove(name)) { added.add(name); } });
                    change.removed().forEach(name -> { if (!added.remove(name)) { removed.add(name); } });
                }
                boolean placed = i + 1 == messages.size() || messages.get(i + 1).role() == Role.ASSISTANT;
                if (placed) {
                    flush(out, added, removed);
                }
                continue;
            }
            if (message.role() == Role.ASSISTANT) {
                flush(out, added, removed);
            }
            out.add(message);
        }
        flush(out, added, removed);
        return out;
    }

    private static boolean isToolChange(Message message) {
        return message.role() == Role.SYSTEM && !message.content().isEmpty()
                && message.content().stream().allMatch(ToolChangeBlock.class::isInstance);
    }

    private static void flush(List<Message> out, Set<String> added, Set<String> removed) {
        if (!added.isEmpty() || !removed.isEmpty()) {
            out.add(new Message(Role.SYSTEM, List.of(new ToolChangeBlock(List.copyOf(added), List.copyOf(removed))), false));
        }
        added.clear();
        removed.clear();
    }
}
