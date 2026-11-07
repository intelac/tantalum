package tantalum.shell.route;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI 兼容路线的模型列表：{@code GET {base-url}/models} 的 {@code data[].id}（照 DSH
 * {@code packages/llm/llm-pi-ai/src/discovery.ts:1-46}）。有密钥时带 {@code Authorization: Bearer}，5 秒超时，不跟跳转。
 * 窗口按模型单独配置的 → llama.cpp 的 {@code meta.n_ctx}、vLLM 的 {@code max_model_len}、LM Studio 等的 {@code context_length}
 * → 路线配置的；后两种之外的来源算“来自配置”。{@code list-models=false} 时不发请求，列表就是允许列表
 * （没写就是默认模型）。日志和错误只写类别和状态码，不写地址、请求头和响应体。
 */
public final class OpenAiModels implements ModelList {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiModels.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final List<String> FIELDS = List.of("/meta/n_ctx", "/max_model_len", "/context_length");

    private final RouteSettings route;
    private final String bearer;
    private final Duration timeout;
    private final HttpClient http;

    /** {@code token}（密钥）为 null 时不发认证头（本机服务）。 */
    public OpenAiModels(RouteSettings route, String token, Duration timeout) {
        this.route = route;
        this.bearer = token;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public List<ModelInfo> read() throws IOException, InterruptedException {
        if (!route.listModels()) {
            List<String> ids = route.allow().isEmpty() ? List.of(route.defaultModel()) : route.allow();
            return ids.stream().map(id -> info(id, null)).toList();
        }
        JsonNode root = fetch();
        List<String> seen = new ArrayList<>();
        List<ModelInfo> models = new ArrayList<>();
        for (JsonNode entry : root.path("data")) {
            String id = entry.path("id").asString(null);
            if (id == null || id.isBlank() || seen.contains(id)) {
                continue;
            }
            seen.add(id);
            if (route.allow().isEmpty() || route.allow().contains(id)) {
                models.add(info(id, entry));
            }
        }
        List<String> missing = route.allow().stream().filter(id -> !seen.contains(id)).toList();
        if (!missing.isEmpty()) {
            LOG.warn("route {}: allowed models not offered by /models: {}", route.name(), String.join(", ", missing));
        }
        return models;
    }

    private JsonNode fetch() throws IOException, InterruptedException {
        URI models = URI.create(route.baseUrl().replaceAll("/+$", "") + "/models");
        HttpRequest.Builder request = HttpRequest.newBuilder(models).timeout(timeout).GET();
        if (bearer != null) {
            request.header("authorization", "Bearer " + bearer);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException slow) {
            throw new Unreadable("超时");
        } catch (ConnectException | java.nio.channels.ClosedChannelException down) {
            throw new Unreadable("连不上");
        } catch (IOException other) {
            throw new Unreadable(other instanceof java.io.InterruptedIOException ? "超时" : "连不上");
        }
        if (response.statusCode() != 200) {
            throw new Unreadable(Integer.toString(response.statusCode()));
        }
        try {
            JsonNode root = MAPPER.readTree(response.body());
            if (!root.path("data").isArray()) {
                throw new Unreadable("返回的不是模型列表");
            }
            return root;
        } catch (JacksonException notJson) {
            throw new Unreadable("返回的不是模型列表");
        }
    }

    private ModelInfo info(String id, JsonNode entry) {
        long window = 0;
        ModelInfo.Source source = ModelInfo.Source.CONFIG;
        if (route.windowOverridden(id)) {
            window = route.configuredWindow(id);
        } else {
            long reported = entry == null ? 0 : reported(entry);
            if (reported > 0) {
                window = reported;
                source = ModelInfo.Source.MODEL;
            } else {
                window = route.configuredWindow(id);
            }
        }
        return new ModelInfo(route.name(), id, id, window, source, route.maxTokens(), route.vision(id), route.openAiLevels(id), true);
    }

    private static long reported(JsonNode entry) {
        for (String field : FIELDS) {
            JsonNode value = entry.at(field);
            if (value.isIntegralNumber() && value.asLong() > 0) {
                return value.asLong();
            }
        }
        return 0;
    }
}
