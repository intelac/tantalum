package tantalum.value.model;

import java.util.List;

/**
 * 会话中途工具变了（core-tools C14）：从这条消息起新增和移除了哪些工具，只出现在 {@link Role#SYSTEM} 消息里。工具列表本身从会话开始
 * 就是全集、不改，变化只以追加的消息表达，已经发出的内容一个字不动，历史里的思考签名才继续有效。
 * {@code unavailable} 是这次之前就已经不能用、现在仍然不能用的工具：小模型在一次变化之后会把最新的说明当成完整名单，
 * 所以每次变化都重申一遍（one-line-journal J7，同 pi）。
 */
public record ToolChangeBlock(List<String> added, List<String> removed, List<String> unavailable) implements ContentBlock {

    public ToolChangeBlock {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        unavailable = unavailable == null ? List.of() : List.copyOf(unavailable);
        if (added.isEmpty() && removed.isEmpty()) {
            throw new IllegalArgumentException("a tool change adds or removes at least one tool");
        }
    }

    public ToolChangeBlock(List<String> added, List<String> removed) {
        this(added, removed, List.of());
    }
}
