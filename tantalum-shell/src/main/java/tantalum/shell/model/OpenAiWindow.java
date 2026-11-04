package tantalum.shell.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI 兼容服务的窗口：{@code GET {base-url}/models} 里 {@code id} 等于配置模型名的那一项，依次看 llama.cpp 的
 * {@code meta.n_ctx}、vLLM 的 {@code max_model_len}、LM Studio 等的 {@code context_length}，取第一个正整数
 * （compaction-threshold T1；pi {@code CA/src/extensions/llama/provider.ts:71-79}）。
 */
public final class OpenAiWindow implements WindowSource {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final List<String> FIELDS = List.of("/meta/n_ctx", "/max_model_len", "/context_length");

    private final URI models;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;

    public OpenAiWindow(String baseUrl, String model, String apiKey, Duration timeout) {
        this.models = URI.create(baseUrl.replaceAll("/+$", "") + "/models");
        this.model = model;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public OptionalLong read() throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(models).timeout(timeout).GET();
        if (apiKey != null) {
            request.header("authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new Unreadable("GET /models returned HTTP " + response.statusCode());
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(response.body());
        } catch (JacksonException notJson) {
            throw new Unreadable("GET /models did not return JSON");
        }
        for (JsonNode entry : root.path("data")) {
            if (model.equals(entry.path("id").asString(null))) {
                return windowOf(entry);
            }
        }
        return OptionalLong.empty();
    }

    private static OptionalLong windowOf(JsonNode entry) {
        for (String field : FIELDS) {
            JsonNode value = entry.at(field);
            if (value.isIntegralNumber() && value.asLong() > 0) {
                return OptionalLong.of(value.asLong());
            }
        }
        return OptionalLong.empty();
    }
}
