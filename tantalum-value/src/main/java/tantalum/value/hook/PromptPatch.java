package tantalum.value.hook;

import java.util.List;
import java.util.Objects;
import tantalum.value.model.ToolSpec;

/** 钩子对 prompt 的不可变变更：追加 system 片段和工具定义。 */
public record PromptPatch(String systemAppend, List<ToolSpec> addTools) {

    public static final PromptPatch NONE = new PromptPatch("", List.of());

    public PromptPatch {
        Objects.requireNonNull(systemAppend, "systemAppend");
        addTools = List.copyOf(addTools);
    }
}
