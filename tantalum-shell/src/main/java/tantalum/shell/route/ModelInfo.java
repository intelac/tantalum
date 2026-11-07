package tantalum.shell.route;

import java.util.Objects;

/**
 * 一个模型能做什么：窗口和它从哪来、输出上限（模型报的和路线配置的较小者）、收不收图片、思考档位，
 * 以及能不能在会话中途增减工具（OpenAI 兼容路线的工具变化写成文字，恒为 true）。{@code name} 是显示名：
 * Claude 的 {@code display_name}，OpenAI 兼容的就是 {@code id}。{@code window} 为 0 表示不知道：不压缩，画面不显示占比。
 */
public record ModelInfo(String route, String id, String name, long window, Source windowSource, long maxTokens, boolean vision,
                        Levels levels, boolean toolChanges) {

    /** 窗口从哪来：模型报告的，或者路线配置的。 */
    public enum Source {
        MODEL, CONFIG
    }

    public ModelInfo {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(windowSource, "windowSource");
        Objects.requireNonNull(levels, "levels");
        name = name == null || name.isBlank() ? id : name;
    }
}
