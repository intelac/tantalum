package tantalum.shell.panel;

import tantalum.shell.plugin.PluginInfo;
import tantalum.value.text.LocalText;

/**
 * 插件的面板（plugin-panel 需求 1）：控制台在对话右边用沙箱 iframe 打开插件 jar 里的一个页面，像 artifacts 那样。
 * 面板拿不到登录态、不能自己联网，数据只能经控制台的消息桥（协议见 {@code docs/panels.md}）。
 * 所属插件在插件页上关掉时面板也就没有了；只有面板的插件在插件页上照样有一个开关。
 */
public interface Panel extends PluginInfo {

    /** 面板 ID：小写字母、数字、连字符，1–40 个字符，全局唯一。也是地址 {@code /panels/<ID>/} 的一段。 */
    String name();

    /** 所属插件，默认就是面板 ID。 */
    default String plugin() {
        return name();
    }

    /** 菜单和标题栏上的名字。 */
    LocalText title();

    /** lucide 图标名（如 {@code activity}）；控制台不认识时用默认图标。 */
    default String icon() {
        return "panel-right";
    }

    /** 面板文件在 classpath 上的目录，以 {@code /} 结尾，如 {@code tantalum/panels/run-overview/}。 */
    String base();

    /** 目录里的入口页面。 */
    default String entry() {
        return "index.html";
    }
}
