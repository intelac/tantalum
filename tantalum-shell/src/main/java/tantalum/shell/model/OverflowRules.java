package tantalum.shell.model;

import java.util.List;
import java.util.Locale;

/**
 * 识别上下文超长错误。默认规则来自 strata 实测、Claude 文档和 pi 的
 * {@code packages/ai/src/utils/overflow.ts}。限流和服务端错误不算超长，即使文字相似。
 */
public final class OverflowRules {

    private final List<String> fragments;

    private OverflowRules(List<String> fragments) {
        this.fragments = fragments.stream().map(fragment -> fragment.toLowerCase(Locale.ROOT)).toList();
    }

    public static OverflowRules of(List<String> fragments) {
        return new OverflowRules(fragments);
    }

    public static OverflowRules defaults() {
        return new OverflowRules(List.of(
                "exceeds the context window",
                "prompt is too long",
                "prompt too long",
                "request_too_large",
                "maximum context length",
                "context_length_exceeded",
                "exceeds the available context size",
                "greater than the context length",
                "reduce the length of the messages"));
    }

    public boolean matches(int status, String message) {
        if (status == 413) {
            return true;
        }
        if (status != 400 || message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return fragments.stream().anyMatch(lower::contains);
    }
}
