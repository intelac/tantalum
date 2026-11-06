package tantalum.shell.route;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tantalum.shell.model.OpenAiThinking;

/**
 * 一条路线的配置（model-picker M1），由 {@code boot} 从 {@code tantalum.claude.*}、{@code tantalum.openai-routes.{名字}.*}
 * 转来，这个包不读全局配置。{@code apiKeyEnv} 只是环境变量名，密钥本身不在这里。{@code allow} 是允许列表；
 * {@code contextWindow} 是读不到窗口时用的值（0 表示不知道）。Claude 的用 {@code defaultEffort}、{@code fallbacks}；
 * OpenAI 兼容的用 {@code baseUrl} 到 {@code overrides}。
 */
public record RouteSettings(String name, String displayName, Kind kind, String apiKeyEnv, String defaultModel, List<String> allow,
                            long maxTokens, long contextWindow, double bytesPerToken, int concurrency, Duration firstChunkTimeout,
                            Duration betweenChunkTimeout, String defaultEffort, boolean fallbacks, String baseUrl, boolean listModels,
                            OpenAiThinking thinking, List<String> efforts, boolean vision, Map<String, Override> overrides) {

    public enum Kind {
        CLAUDE, OPENAI
    }

    /** OpenAI 兼容路线里某个模型单独的设置，没写的项为 null，跟路线（M5）。 */
    public record Override(OpenAiThinking thinking, List<String> efforts, String defaultEffort, Boolean vision, Long contextWindow) {}

    public RouteSettings {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(defaultModel, "defaultModel");
        allow = List.copyOf(allow);
        efforts = efforts == null ? List.of() : List.copyOf(efforts);
        overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
        thinking = thinking == null ? OpenAiThinking.NONE : thinking;
        displayName = displayName == null || displayName.isBlank() ? name : displayName;
    }

    /** 这个模型的思考写法：有单独设置就按它，否则按路线。 */
    public OpenAiThinking thinking(String model) {
        Override override = overrides.get(model);
        return override != null && override.thinking() != null ? override.thinking() : thinking;
    }

    /** OpenAI 兼容模型的档位（M5）。 */
    public Levels openAiLevels(String model) {
        Override override = overrides.get(model);
        if (override != null && override.thinking() != null) {
            return Levels.openAi(override.thinking(), override.efforts(), override.defaultEffort());
        }
        return Levels.openAi(thinking, efforts, defaultEffort);
    }

    /** OpenAI 兼容模型收不收图片。 */
    public boolean vision(String model) {
        Override override = overrides.get(model);
        return override != null && override.vision() != null ? override.vision() : vision;
    }

    /** 配置给这个模型的窗口：单独设置的优先；0 表示没配。 */
    public long configuredWindow(String model) {
        Override override = overrides.get(model);
        return override != null && override.contextWindow() != null && override.contextWindow() > 0 ? override.contextWindow() : contextWindow;
    }

    /** 单独给这个模型配了窗口（它优先于接口报的）。 */
    public boolean windowOverridden(String model) {
        Override override = overrides.get(model);
        return override != null && override.contextWindow() != null && override.contextWindow() > 0;
    }

    /**
     * 请求会不会离开这台机器（{@code /api/models} 的 {@code cloud}、启动日志）：Claude 总是；OpenAI 兼容的地址不是回环时是。
     */
    public boolean cloud() {
        if (kind == Kind.CLAUDE) {
            return true;
        }
        String host = URI.create(baseUrl).getHost();
        return host == null || !(host.equals("localhost") || host.equals("::1") || host.equals("[::1]") || host.startsWith("127."));
    }
}
