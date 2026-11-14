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
        return LocalText.en("Shortens tool results over the limit, keeping the beginning and the end and noting how many characters "
                        + "were left out in between. File reads are left whole.")
                .zh("工具结果超过上限时保留开头和结尾，中间注明省掉了多少字符。读文件的结果不截。")
                .ja("上限を超えたツール結果は先頭と末尾を残し、間で省いた文字数を書き添えます。ファイルの読み取り結果は切り詰めません。");
    }

    /** 模型按行号读文件的结果：不截（照 DSH {@code dsh-spill-policy} 跳过 {@code read}，免得截了再读、读了又截）。 */
    static final String READ_FILE = "read_file";

    /**
     * 超过上限时留首尾、中间写明省掉了多少字符（照 DSH {@code dsh-spill-policy}“保留首尾”；tool-scenarios-2 需求 6.4a）。
     * 工具自己写在末尾的话（退出码、完整输出存在哪个文件）因此不会被这一层切掉。开头给一半，末尾给另一半；不在代理对中间切开。
     * 不另存完整结果：这个插件在服务器上，模型用的读文件工具在用户电脑上，读不到服务器的文件；命令的完整输出由连接程序那一层存。
     */
    @Override
    public ToolOutput afterToolExecution(ToolContext context, String tool, Json arguments, ToolOutput output) {
        String text = output.text();
        int maxChars = (int) Math.min(Integer.MAX_VALUE, context.params().number("maxChars", this.maxChars));
        if (text.length() <= maxChars || READ_FILE.equals(tool)) {
            return output;
        }
        int head = maxChars / 2;
        if (head > 0 && Character.isHighSurrogate(text.charAt(head - 1))) {
            head--;
        }
        int tailStart = text.length() - (maxChars - maxChars / 2);
        if (tailStart < text.length() && Character.isLowSurrogate(text.charAt(tailStart))) {
            tailStart++;
        }
        int omitted = tailStart - head;
        return new ToolOutput(text.substring(0, head) + "\n[... " + omitted + " characters omitted ...]\n" + text.substring(tailStart),
                output.error(), output.images());
    }
}
