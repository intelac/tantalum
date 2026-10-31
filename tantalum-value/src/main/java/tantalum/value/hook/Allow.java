package tantalum.value.hook;

import java.util.Objects;

/** 放行，可携带数据变更，不改变流程。 */
public record Allow(PromptPatch patch) implements HookResult {

    public static final Allow PLAIN = new Allow(PromptPatch.NONE);

    public Allow {
        Objects.requireNonNull(patch, "patch");
    }
}
