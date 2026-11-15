package tantalum.examples;

import tantalum.shell.panel.Panel;
import tantalum.value.text.LocalText;

/**
 * 示例面板“实时监视”（live-monitor 需求 4）：服务器流水线的架构图，当前会话的事件走过哪个阶段，光点就沿着连线走到那个组件，
 * 组件上显示这一步的数值。数据只来自消息桥的 monitor 消息（服务器只在内存里推，不进 journal）。页面在
 * {@code resources/tantalum/panels/live-monitor/}，没有构建步骤。
 */
public final class LiveMonitorPanel implements Panel {

    public static final String NAME = "live-monitor";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public LocalText title() {
        return LocalText.of("Live monitor", "实时监视", "ライブモニター");
    }

    @Override
    public String icon() {
        return "activity";
    }

    @Override
    public String base() {
        return "tantalum/panels/live-monitor/";
    }

    @Override
    public LocalText description() {
        return LocalText.en("A panel with the server's pipeline as a diagram: a dot follows each event of the session through the stages, live.")
                .zh("一个面板，画出服务器的流水线：这个会话的每个事件走到哪一步，光点就实时沿着连线走到哪个组件。")
                .ja("サーバーのパイプラインを図で表示するパネル。会話のイベントが進む段階を、光点がリアルタイムでたどります。");
    }
}
