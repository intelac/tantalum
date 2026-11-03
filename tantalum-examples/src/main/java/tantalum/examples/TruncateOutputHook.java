package tantalum.examples;

import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;

/** 输出截断：处理器钩子，只改数据，不改流程。 */
public final class TruncateOutputHook implements AfterToolHook {

    private final int maxChars;

    public TruncateOutputHook(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive but was " + maxChars);
        }
        this.maxChars = maxChars;
    }

    @Override
    public String plugin() {
        return "truncateOutput";
    }

    @Override
    public String description() {
        return "工具结果超过上限时截断，并注明截掉了多少字符。";
    }

    /** 不在代理对中间切开，否则截断处是一个坏字符。 */
    @Override
    public ToolOutput afterToolExecution(ToolContext context, String tool, Json arguments, ToolOutput output) {
        String text = output.text();
        if (text.length() <= maxChars) {
            return output;
        }
        int cut = Character.isHighSurrogate(text.charAt(maxChars - 1)) ? maxChars - 1 : maxChars;
        int dropped = text.length() - cut;
        return new ToolOutput(text.substring(0, cut) + "\n(" + dropped + " more characters truncated)", output.error());
    }
}
