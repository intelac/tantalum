package tantalum.value.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tantalum.value.json.Json;

/**
 * 放行，可携带数据变更，不改变流程。{@code notes} 是要记进会话状态的记录（{@code SessionState.note(key)}），
 * 只在 {@code onAnswer} 的放行上生效（waiting-state D2：工具审批的“这个会话里一直允许”），别处忽略；通常为空。
 */
public record Allow(PromptPatch patch, List<SessionNote> notes) implements HookResult {

    public static final Allow PLAIN = new Allow(PromptPatch.NONE, List.of());

    /** 记进会话的一条：{@code key} 通常是插件名，{@code value} 和已有的同名记录合并（新键覆盖旧键）。 */
    public record SessionNote(String key, Json.Obj value) {
        public SessionNote {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    public Allow {
        Objects.requireNonNull(patch, "patch");
        notes = List.copyOf(notes);
    }

    public Allow(PromptPatch patch) {
        this(patch, List.of());
    }

    public Allow withNote(String key, Json.Obj value) {
        List<SessionNote> added = new ArrayList<>(notes);
        added.add(new SessionNote(key, value));
        return new Allow(patch, added);
    }
}
