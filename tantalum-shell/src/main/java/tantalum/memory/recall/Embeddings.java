package tantalum.memory.recall;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.plugin.JsonText;
import tantalum.value.json.Json;

/**
 * 记忆插件用的向量服务（memory-final-plan 第 9 节）：把文字变成向量，按意思找回时用。接的是 OpenAI 兼容的 {@code POST <url>/embeddings}
 * （本机的 ollama、llama.cpp、vLLM 都是这个样子）。地址和模型是记忆插件的参数（插件页上，管理员定；默认值来自 {@code tantalum.memory-vectors.*}）；没填地址时关着，调用返回空。
 * <p>返回的向量已经归一化（长度为 1），所以两个向量的点积就是余弦相似度。{@code dimensions} 大于 0 时只取前这么多维再归一化
 * （支持“套娃”截断的模型可以这样省空间；不支持的模型不要设）。{@code queryPrefix} 加在“拿来找的那句话”前面
 * （有些模型要求问句带一句说明，被找的文字不带）。
 * <p>文字会发给配置的地址：配成本机服务时不出这台机器。失败、超时都返回空，调用方照没有向量的办法做，不抛出。
 */
public final class Embeddings {

    private static final Logger LOG = LoggerFactory.getLogger(Embeddings.class);
    /** 一次请求最多这么多段。 */
    public static final int MAX_BATCH = 32;
    /** 一段最多这么多字符，长的截断（向量只是用来找，不需要全文）。 */
    static final int MAX_CHARS = 4000;

    /** 配置。{@code url} 为 null 或空白时关着。 */
    public record Settings(String url, String model, int dimensions, String queryPrefix, Duration timeout) {

        public static final Settings OFF = new Settings(null, null, 0, "", Duration.ofSeconds(10));

        public Settings {
            queryPrefix = queryPrefix == null ? "" : queryPrefix;
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        }

        public boolean on() {
            return url != null && !url.isBlank() && model != null && !model.isBlank();
        }
    }

    private final Settings settings;
    private final HttpClient http;

    public Embeddings(Settings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean on() {
        return settings.on();
    }

    /** 每段文字一个向量，顺序相同；{@code query} 为真时这些是“拿来找的话”。关着、失败或超时为空。 */
    public Optional<List<float[]>> embed(List<String> texts, boolean query) {
        if (!settings.on() || texts.isEmpty() || texts.size() > MAX_BATCH) {
            return Optional.empty();
        }
        List<String> input = texts.stream().map(text -> (query ? settings.queryPrefix() : "") + (text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text))
                .toList();
        try {
            String body = JsonText.write(new Json.Obj(Map.of("model", new Json.Str(settings.model()),
                    "input", new Json.Arr(input.stream().<Json>map(Json.Str::new).toList()))));
            String base = settings.url().endsWith("/") ? settings.url().substring(0, settings.url().length() - 1) : settings.url();
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/embeddings")).timeout(settings.timeout())
                    .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warn("embedding service answered {}", response.statusCode());
                return Optional.empty();
            }
            return parse(response.body(), texts.size(), settings.dimensions());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception failure) {
            LOG.warn("embedding failed: {}", failure.toString());
            return Optional.empty();
        }
    }

    /** 解开回答：按 {@code index} 排回原来的顺序，截到 {@code dimensions} 维（0 不截），归一化。个数或内容不对为空。 */
    static Optional<List<float[]>> parse(String body, int expected, int dimensions) {
        if (!(JsonText.read(body) instanceof Json.Obj answer) || !(answer.fields().get("data") instanceof Json.Arr data)
                || data.items().size() != expected) {
            return Optional.empty();
        }
        float[][] vectors = new float[expected][];
        for (int i = 0; i < expected; i++) {
            if (!(data.items().get(i) instanceof Json.Obj item) || !(item.fields().get("embedding") instanceof Json.Arr values)
                    || values.items().isEmpty()) {
                return Optional.empty();
            }
            int index = item.fields().get("index") instanceof Json.Num number ? number.value().intValue() : i;
            if (index < 0 || index >= expected || vectors[index] != null) {
                return Optional.empty();
            }
            int size = dimensions > 0 ? Math.min(dimensions, values.items().size()) : values.items().size();
            float[] vector = new float[size];
            double norm = 0;
            for (int d = 0; d < size; d++) {
                if (!(values.items().get(d) instanceof Json.Num value)) {
                    return Optional.empty();
                }
                vector[d] = value.value().floatValue();
                norm += vector[d] * vector[d];
            }
            if (norm == 0) {
                return Optional.empty();
            }
            float scale = (float) (1 / Math.sqrt(norm));
            for (int d = 0; d < size; d++) {
                vector[d] *= scale;
            }
            vectors[index] = vector;
        }
        return Optional.of(new ArrayList<>(List.of(vectors)));
    }
}
