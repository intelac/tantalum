package tantalum.value.model;

import java.util.List;

/**
 * 按需出现的工具（browser-on-demand）：这个会话里调过 {@code after} 里任何一个工具以后，{@code add} 里的工具从下一次调用模型起开着，
 * 会话内不再撤掉（用户在插件页关掉的除外）。服务器组装上下文时按用户的开关算好，内核照着做。
 */
public record ToolUnlock(List<String> after, List<String> add) {

    public ToolUnlock {
        after = List.copyOf(after);
        add = List.copyOf(add);
    }
}
