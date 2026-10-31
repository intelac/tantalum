package tantalum.examples;

import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;

/** 输出截断：处理器钩子，只改数据，不改流程。 */
public final class TruncateOutputHook implements AfterToolHook {

    private final int maxChars;

    public TruncateOutputHook(int maxChars) {
        this.maxChars = maxChars;
    }

    @Override
    public ToolOutput afterToolExecution(ToolContext context, String tool, Json arguments, ToolOutput output) {
        if (output.text().length() <= maxChars) {
            return output;
        }
        int dropped = output.text().length() - maxChars;
        return new ToolOutput(output.text().substring(0, maxChars) + "\n(" + dropped + " more characters truncated)", output.error());
    }
}
