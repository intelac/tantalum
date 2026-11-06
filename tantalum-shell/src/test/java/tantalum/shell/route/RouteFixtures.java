package tantalum.shell.route;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import tantalum.shell.model.OpenAiThinking;

/** 测试用的路线配置。 */
final class RouteFixtures {

    private RouteFixtures() {}

    static RouteSettings claude(List<String> allow) {
        return new RouteSettings("claude", "Claude", RouteSettings.Kind.CLAUDE, "ANTHROPIC_API_KEY", "claude-sonnet-5-5", allow, 64_000,
                200_000, 1.5, 4, Duration.ofSeconds(300), Duration.ofSeconds(300), "medium", false, null, false, null, null, true, Map.of());
    }

    static RouteSettings openAi(String name, String keyVariable, OpenAiThinking thinking, long window) {
        return new RouteSettings(name, name, RouteSettings.Kind.OPENAI, keyVariable, "qwen", List.of(), 16_384, window, 1.75, 1,
                Duration.ofSeconds(300), Duration.ofSeconds(60), null, false, "http://127.0.0.1:1/secret-path/v1", true, thinking, null,
                false, Map.of());
    }

    static ModelInfo model(String route, String id, Levels levels, boolean vision, boolean toolChanges) {
        return new ModelInfo(route, id, "Name of " + id, 100_000, ModelInfo.Source.MODEL, 16_384, vision, levels, toolChanges);
    }
}
