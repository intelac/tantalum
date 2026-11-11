package tantalum.examples;

import java.util.List;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Configurable;
import tantalum.shell.plugin.Param;
import tantalum.shell.plugin.PluginInfo;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;
import tantalum.value.text.LocalText;

/**
 * 输出截断：处理器钩子，只改数据，不改流程。
 *
 * <p>上限是用户自己的参数 {@code maxChars}，每次调用时读当时的值；构造时给的值（服务器配置）是插件自带的默认。
 */
public final class TruncateOutputHook implements AfterToolHook, PluginInfo, Configurable {

    /** 页面上能填的范围；服务器配置写在范围外时，范围跟着放宽，不让启动失败。 */
    private static final long LOWEST = 100;
    private static final long HIGHEST = 1_000_000;

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
    public List<Param> params() {
        return List.of(Param.number("maxChars", maxChars).range(Math.min(LOWEST, maxChars), Math.max(HIGHEST, maxChars))
                .label(LocalText.en("Longest tool result, in characters").zh("工具结果最多多少字符").ja("ツール結果の最大文字数")));
    }

    @Override
    public LocalText description() {
        // 例子插件示范带三种语言的写法：控制台按看的人的语言选，没有就用英文
        return LocalText.en("Truncates tool results over the limit and notes how many characters were cut.")
                .zh("工具结果超过上限时截断，并注明截掉了多少字符。")
                .ja("上限を超えたツール結果を切り詰め、削った文字数を書き添えます。");
    }

    /** 不在代理对中间切开，否则截断处是一个坏字符。 */
    @Override
    public ToolOutput afterToolExecution(ToolContext context, String tool, Json arguments, ToolOutput output) {
        String text = output.text();
        int maxChars = (int) Math.min(Integer.MAX_VALUE, context.params().number("maxChars", this.maxChars));
        if (text.length() <= maxChars) {
            return output;
        }
        int cut = Character.isHighSurrogate(text.charAt(maxChars - 1)) ? maxChars - 1 : maxChars;
        int dropped = text.length() - cut;
        return new ToolOutput(text.substring(0, cut) + "\n(" + dropped + " more characters truncated)", output.error());
    }
}
