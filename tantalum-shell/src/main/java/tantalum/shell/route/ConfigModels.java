package tantalum.shell.route;

import java.util.List;
import tantalum.shell.model.ClaudeClient;

/**
 * 只按配置给出模型列表，不调接口：测试和嵌入使用换掉了客户端的路线（{@link RouteClients}），和列表还没读到时给压缩用的
 * 模型信息。列表是允许列表（没写就是默认模型）；窗口是配置的，来源 {@code CONFIG}。Claude 的五档都给，图片都收。
 */
final class ConfigModels implements ModelList {

    private final RouteSettings route;

    ConfigModels(RouteSettings route) {
        this.route = route;
    }

    @Override
    public List<ModelInfo> read() {
        List<String> ids = route.allow().isEmpty() ? List.of(route.defaultModel()) : route.allow();
        return ids.stream().map(id -> info(route, id)).toList();
    }

    static ModelInfo info(RouteSettings route, String model) {
        long window = route.configuredWindow(model);
        if (route.kind() == RouteSettings.Kind.CLAUDE) {
            return new ModelInfo(route.name(), model, model, window, ModelInfo.Source.CONFIG, route.maxTokens(), true,
                    Levels.claude(Levels.CLAUDE, route.defaultEffort()), ClaudeClient.supportsToolChanges(model));
        }
        return new ModelInfo(route.name(), model, model, window, ModelInfo.Source.CONFIG, route.maxTokens(), route.vision(model),
                route.openAiLevels(model), true);
    }
}
