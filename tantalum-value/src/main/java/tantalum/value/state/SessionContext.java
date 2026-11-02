package tantalum.value.state;

import java.util.List;
import java.util.Objects;
import tantalum.value.model.ToolSpec;

/**
 * 会话第一次发给模型的 system 和工具，之后固定不变：thinking 签名绑定它们，一改旧块就失效。
 * {@code sections} 是最近告知模型的可变内容；{@code compaction} 是同一时刻快照的压缩设置，为空表示这个会话不压缩。
 */
public record SessionContext(String system, List<ToolSpec> tools, CompactionSettings compaction, List<ContextSection> sections) {

    public SessionContext {
        Objects.requireNonNull(system, "system");
        tools = List.copyOf(tools);
        sections = List.copyOf(sections);
    }

    /** 只换告知过模型的可变内容，system 和工具不动。 */
    public SessionContext withSections(List<ContextSection> newSections) {
        return new SessionContext(system, tools, compaction, newSections);
    }
}
