package tantalum.kernel.projection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.HookTextBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.Role;
import tantalum.value.model.SystemUpdates;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionContext.HookState.HookTool;
import tantalum.value.state.SessionState;

/** 从状态投影出一次模型调用的 {@code Prompt}。消息取会话的视图（压缩过就是替换段加新消息）。回放时重新计算即可还原，不单独落盘。 */
public final class PromptProjection {

    private PromptProjection() {}

    /**
     * 会话冻结的 system 和工具；普通调用和摘要调用都从这里取，两者前缀相同。会话开始时没开的工具标为 deferred：
     * 定义照发、模型看不见，等历史里的工具变化把它加进来（core-tools C14）。钩子加的工具接在后面，永远 deferred，同样靠历史里的
     * 工具变化出现（hook-patch）。钩子的说明在消息里，system 字符串就是冻结的那份。
     */
    public static Prompt project(SessionState session) {
        SessionContext context = session.context();
        List<ToolSpec> tools = new ArrayList<>();
        for (ToolSpec tool : context.tools()) {
            tools.add(tool.deferred(context.initialTools() != null && !context.initialTools().contains(tool.name())));
        }
        for (HookTool hook : context.hookState().tools()) {
            tools.add(hook.tool().deferred(true));
        }
        return new Prompt(context.system(), tools, session.view());
    }

    /**
     * system 更新（工具变化、钩子的说明）要紧跟在用户消息后面，后面要么是模型的回复、要么是最后一条（Claude 的规则）。正常情况下它就在提问和回复
     * 之间；一次提问没等到回复就失败、下一次提问又来时，它后面跟的是用户消息。这时把它挪到下一处合法的位置，和那里的更新合并成一条：
     * 文字按顺序接在一起，工具变化合并成净变化。被挪的那条后面没有回复，没有任何思考签名绑定过它，挪动不影响校验。纯函数，回放结果相同。
     */
    public static List<Message> placeSystemUpdates(List<Message> messages) {
        List<Message> out = new ArrayList<>();
        List<HookTextBlock> texts = new ArrayList<>();
        Set<String> added = new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (isSystemUpdate(message)) {
                for (ContentBlock block : message.content()) {
                    switch (block) {
                        case HookTextBlock text -> texts.add(text);
                        case ToolChangeBlock change -> {
                            change.added().forEach(name -> { if (!removed.remove(name)) { added.add(name); } });
                            change.removed().forEach(name -> { if (!added.remove(name)) { removed.add(name); } });
                        }
                        default -> throw new IllegalStateException("not a system update: " + block);
                    }
                }
                boolean placed = i + 1 == messages.size() || messages.get(i + 1).role() == Role.ASSISTANT;
                if (placed) {
                    flush(out, texts, added, removed);
                }
                continue;
            }
            if (message.role() == Role.ASSISTANT) {
                flush(out, texts, added, removed);
            }
            out.add(message);
        }
        flush(out, texts, added, removed);
        return out;
    }

    /** 工具变化或钩子说明的系统消息；换模型的说明不算，它由各客户端并进用户消息。规则在 {@link SystemUpdates}。 */
    public static boolean isSystemUpdate(Message message) {
        return SystemUpdates.isSystemUpdate(message);
    }

    private static void flush(List<Message> out, List<HookTextBlock> texts, Set<String> added, Set<String> removed) {
        List<ContentBlock> blocks = new ArrayList<>(texts);
        if (!added.isEmpty() || !removed.isEmpty()) {
            blocks.add(new ToolChangeBlock(List.copyOf(added), List.copyOf(removed)));
        }
        if (!blocks.isEmpty()) {
            out.add(new Message(Role.SYSTEM, blocks, false));
        }
        texts.clear();
        added.clear();
        removed.clear();
    }
}
