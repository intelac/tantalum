package tantalum.value.model;

import java.util.Objects;

/**
 * 插件在提交时附在用户消息上的一段文字（memory-plugin 需求 1.2），排在文字、附件和技能之后。发给模型时包在
 * {@code <attached plugin="…">} 里；{@code plugin} 给对话页和轨迹页标来源。
 */
public record AttachedBlock(String plugin, String text) implements ContentBlock {

    public AttachedBlock {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(text, "text");
        if (plugin.isEmpty() || text.isBlank()) {
            throw new IllegalArgumentException("an attached text names its plugin and says something");
        }
    }

    /** 发给模型的样子。 */
    public String wrapped() {
        return "<attached plugin=\"" + plugin + "\">\n" + text.strip() + "\n</attached>";
    }
}
