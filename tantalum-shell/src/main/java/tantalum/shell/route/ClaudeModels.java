package tantalum.shell.route;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.beta.models.BetaModelInfo;
import com.anthropic.models.models.ModelCapabilities;
import com.anthropic.models.models.ModelListPage;
import com.anthropic.models.models.ModelListParams;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.model.ClaudeClient;

/**
 * Claude 路线的模型列表：Models 接口（{@code GET /v1/models}，翻完所有页）。只留支持 {@code adaptive} 思考的（M3：我们的请求
 * 总是发 adaptive，只收 {@code budget_tokens} 的老模型会 400）；有允许列表时只留列表里的。窗口是 {@code max_input_tokens}，
 * 开着备用模型时再取 {@code allowed_fallback_models} 里各模型的窗口中最小的；输出上限取模型报的和路线配置的
 * 较小者；收不收图片看 {@code image_input}；档位见 {@link Levels#claude}；能不能中途增减工具来自 {@code ClaudeClient}。
 * 日志只写模型 id 和错误类别。
 */
public final class ClaudeModels implements ModelList {

    private static final Logger LOG = LoggerFactory.getLogger(ClaudeModels.class);
    /** 读 {@code allowed_fallback_models} 要带的 beta 头（SDK 3.3.0 的 {@code allowedFallbackModels()}）。 */
    static final String FALLBACK_LIST_BETA = "server-side-fallback-2026-06-01";
    private static final long PAGE = 1000;

    private final AnthropicClient client;
    private final String route;
    private final List<String> allow;
    private final String defaultEffort;
    private final boolean fallbacks;
    private final long maxTokens;
    private final RequestOptions options;

    public ClaudeModels(AnthropicClient client, String route, List<String> allow, String defaultEffort, boolean fallbacks,
                        long maxTokens, Duration timeout) {
        this.client = client;
        this.route = route;
        this.allow = List.copyOf(allow);
        this.defaultEffort = defaultEffort;
        this.fallbacks = fallbacks;
        this.maxTokens = maxTokens;
        this.options = RequestOptions.builder().timeout(timeout).build();
    }

    @Override
    public List<ModelInfo> read() throws IOException {
        Map<String, com.anthropic.models.models.ModelInfo> listed = new LinkedHashMap<>();
        try {
            ModelListPage page = client.models().list(ModelListParams.builder().limit(PAGE).build(), options);
            while (true) {
                page.items().forEach(model -> listed.put(model.id(), model));
                if (!page.hasNextPage()) {
                    break;
                }
                page = page.nextPage();
            }
        } catch (AnthropicServiceException failure) {
            throw new Unreadable(Integer.toString(failure.statusCode()));
        } catch (AnthropicIoException failure) {
            throw new Unreadable(timedOut(failure) ? "超时" : "连不上");
        } catch (RuntimeException malformed) {
            throw new Unreadable("返回的不是模型列表");
        }
        List<String> dropped = listed.values().stream().filter(model -> !adaptive(model)).map(com.anthropic.models.models.ModelInfo::id).toList();
        if (!dropped.isEmpty()) {
            LOG.info("route {}: not offering {}: no adaptive thinking", route, String.join(", ", dropped));
        }
        List<String> missing = allow.stream().filter(id -> !listed.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            LOG.warn("route {}: allowed models not offered by the Models API: {}", route, String.join(", ", missing));
        }
        List<ModelInfo> models = new ArrayList<>();
        for (com.anthropic.models.models.ModelInfo model : listed.values()) {
            if (!adaptive(model) || (!allow.isEmpty() && !allow.contains(model.id()))) {
                continue;
            }
            models.add(info(model, listed));
        }
        return models;
    }

    static boolean timedOut(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.InterruptedIOException || cause instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static boolean adaptive(com.anthropic.models.models.ModelInfo model) {
        return model.capabilities().map(capabilities -> capabilities.thinking().types().adaptive().supported()).orElse(false);
    }

    private ModelInfo info(com.anthropic.models.models.ModelInfo model, Map<String, com.anthropic.models.models.ModelInfo> listed) {
        long window = model.maxInputTokens().orElse(0L);
        if (fallbacks && window > 0) {
            window = withFallbacks(model.id(), window, listed);
        }
        long cap = model.maxTokens().filter(tokens -> tokens > 0).map(tokens -> Math.min(tokens, maxTokens)).orElse(maxTokens);
        Optional<ModelCapabilities> capabilities = model.capabilities();
        boolean vision = capabilities.map(c -> c.imageInput().supported()).orElse(false);
        return new ModelInfo(route, model.id(), model.displayName(), window, window > 0 ? ModelInfo.Source.MODEL : ModelInfo.Source.CONFIG,
                cap, vision, Levels.claude(efforts(capabilities), defaultEffort), ClaudeClient.supportsToolChanges(model.id()));
    }

    /** 这个模型支持的 effort 档位名。 */
    private static List<String> efforts(Optional<ModelCapabilities> capabilities) {
        List<String> supported = new ArrayList<>();
        capabilities.map(ModelCapabilities::effort).ifPresent(effort -> {
            if (effort.low().supported()) {
                supported.add("low");
            }
            if (effort.medium().supported()) {
                supported.add("medium");
            }
            if (effort.high().supported()) {
                supported.add("high");
            }
            if (effort.xhigh().map(level -> level.supported()).orElse(false)) {
                supported.add("xhigh");
            }
            if (effort.max().supported()) {
                supported.add("max");
            }
        });
        return supported;
    }

    /** 备用模型可能接手，窗口取它们中最小的；读不到备用模型的窗口时只用主模型的。 */
    private long withFallbacks(String id, long window, Map<String, com.anthropic.models.models.ModelInfo> listed) {
        try {
            BetaModelInfo info = client.beta().models().retrieve(id,
                    com.anthropic.models.beta.models.ModelRetrieveParams.builder().addBeta(FALLBACK_LIST_BETA).build(), options);
            long smallest = window;
            for (String fallback : info.allowedFallbackModels().orElse(List.of())) {
                Optional<Long> other = Optional.ofNullable(listed.get(fallback)).flatMap(com.anthropic.models.models.ModelInfo::maxInputTokens)
                        .or(() -> client.models().retrieve(fallback, com.anthropic.models.models.ModelRetrieveParams.none(), options).maxInputTokens())
                        .filter(tokens -> tokens > 0);
                if (other.isEmpty()) {
                    return window;
                }
                smallest = Math.min(smallest, other.get());
            }
            return smallest;
        } catch (RuntimeException unreadable) {
            LOG.warn("route {}: cannot read the fallback models of {}; using its own window", route, id);
            return window;
        }
    }
}
