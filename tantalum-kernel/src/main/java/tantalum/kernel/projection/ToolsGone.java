package tantalum.kernel.projection;

import java.util.List;

/**
 * 压缩之后告诉模型哪些工具已经不能用了（one-line-journal J7）。原来的工具变化消息可能已经压进摘要，
 * 摘要里还提到这些工具，模型会以为还能调用。文字同 pi 的说法，所有模型相同。
 */
public final class ToolsGone {

    private ToolsGone() {}

    public static String text(List<String> tools) {
        return "These tools were available earlier in this conversation and are no longer available. Do not call them: "
                + String.join(", ", tools) + ".";
    }
}
