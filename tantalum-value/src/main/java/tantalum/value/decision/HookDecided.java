package tantalum.value.decision;

import java.util.List;
import java.util.Objects;
import tantalum.value.hook.HookPoint;
import tantalum.value.id.RunRef;

/**
 * 一个钩子做了决定：拦截、要求工作，或放行并带补丁。只放行、没有补丁时不报。由 HookChain 每个钩子报一次，回放时重新算出。
 * 补丁只报长度和工具名：原文已在发给模型的 prompt 里，事实要推给每个 SSE 连接。
 * {@code reason} 只在 STOP 时有值，{@code workType} 只在 NEED_WORK 时有值，{@code toolCall} 只在工具调用前有值。
 */
public record HookDecided(RunRef run, String hook, HookPoint point, Kind kind, String reason,
                          int systemChars, List<String> tools, String toolCall, String workType) implements Fact {

    public enum Kind { STOP, NEED_WORK, PATCH }

    public HookDecided {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(hook, "hook");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(kind, "kind");
        tools = List.copyOf(tools);
    }

    @Override
    public String type() {
        return "HookDecided";
    }
}
