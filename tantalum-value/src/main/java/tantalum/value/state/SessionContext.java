package tantalum.value.state;

import java.util.List;
import java.util.Objects;
import tantalum.value.model.ToolSpec;

/** 会话第一次发给模型的 system 和工具，之后固定不变：thinking 签名绑定它们，一改旧块就失效。{@code dynamic} 是最近告知模型的可变内容。
 * {@code compaction} 是同一时刻快照的压缩设置，为空表示这个会话不压缩。{@code sections} 是分段的可变内容，旧事件为空。 */
public record SessionContext(String system, List<ToolSpec> tools, String dynamic, CompactionSettings compaction, List<ContextSection> sections) {

    public SessionContext {
        Objects.requireNonNull(system, "system");
        tools = List.copyOf(tools);
        sections = sections == null ? null : List.copyOf(sections);
    }

    public SessionContext(String system, List<ToolSpec> tools, String dynamic, CompactionSettings compaction) {
        this(system, tools, dynamic, compaction, null);
    }

    public SessionContext(String system, List<ToolSpec> tools, String dynamic) {
        this(system, tools, dynamic, null, null);
    }

    /** 只换告知过模型的可变内容，system 和工具不动。 */
    public SessionContext withDynamic(String newDynamic, List<ContextSection> newSections) {
        return new SessionContext(system, tools, newDynamic, compaction, newSections);
    }

    public SessionContext withDynamic(String newDynamic) {
        return withDynamic(newDynamic, sections);
    }
}
