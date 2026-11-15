package tantalum.examples;

import tantalum.shell.panel.Panel;
import tantalum.value.text.LocalText;

/**
 * 示例面板“本轮概览”（plugin-panel 需求 5）：在对话右边显示当前会话最近一轮的调用树缩略，运行时跟着实时轨迹更新，
 * 点一行在控制台里打开这次运行的详情。页面在 {@code resources/tantalum/panels/run-overview/}，只用消息桥，没有构建步骤，
 * 当作写面板的范例（协议见 docs/panels.md）。
 */
public final class RunOverviewPanel implements Panel {

    public static final String NAME = "run-overview";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public LocalText title() {
        return LocalText.of("Run overview", "本轮概览", "今回の概要");
    }

    @Override
    public String icon() {
        return "list-tree";
    }

    @Override
    public String base() {
        return "tantalum/panels/run-overview/";
    }

    @Override
    public LocalText description() {
        return LocalText.en("A panel beside the chat with the latest turn's steps, model calls and tools, updated live.")
                .zh("在对话旁边的面板里显示最近一轮的步骤、模型调用和工具，实时更新。")
                .ja("会話の横のパネルに、最新のターンのステップ、モデル呼び出し、ツールをリアルタイムで表示します。");
    }
}
