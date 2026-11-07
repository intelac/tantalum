package tantalum.shell.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.SystemUpdates;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolSpec;

/** 会话中途的工具变化，给不支持追加式工具变化的客户端用：把历史里的变化折算成现在开着的工具。 */
final class ToolChanges {

    private ToolChanges() {}

    /** 不是 deferred 的工具，按历史里的变化依次加上、去掉；保持 {@code prompt.tools()} 的顺序。 */
    static List<ToolSpec> active(Prompt prompt) {
        Set<String> active = new LinkedHashSet<>();
        prompt.tools().stream().filter(tool -> !tool.deferred()).forEach(tool -> active.add(tool.name()));
        for (Message message : prompt.messages()) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolChangeBlock change) {
                    active.addAll(change.added());
                    change.removed().forEach(active::remove);
                }
            }
        }
        return prompt.tools().stream().filter(tool -> active.contains(tool.name())).map(tool -> tool.deferred(false)).toList();
    }

    /** 有没有工具变化或 deferred 的工具：有的话请求要带对应的 beta，不能交给不认识它们的备用模型。 */
    static boolean present(Prompt prompt) {
        return prompt.tools().stream().anyMatch(ToolSpec::deferred) || prompt.messages().stream()
                .anyMatch(message -> message.content().stream().anyMatch(ToolChangeBlock.class::isInstance));
    }

    static String describe(ToolChangeBlock change) {
        StringBuilder text = new StringBuilder(SystemUpdates.HEADER).append("Your tools changed.");
        if (!change.added().isEmpty()) {
            text.append(" Now available: ").append(String.join(", ", change.added())).append('.');
        }
        if (!change.removed().isEmpty()) {
            text.append(" No longer available: ").append(String.join(", ", change.removed())).append('.');
        }
        if (!change.unavailable().isEmpty()) {
            text.append(" Still unavailable: ").append(String.join(", ", change.unavailable())).append('.');
        }
        return text.toString();
    }
}
