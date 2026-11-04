package tantalum.shell.model;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.models.beta.models.BetaModelInfo;
import com.anthropic.models.beta.models.ModelRetrieveParams;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Claude 的窗口：Models 接口的 {@code max_input_tokens}（compaction-threshold T1）。开着备用模型时服务器可能把请求转给
 * 备用模型，所以再读 {@code allowed_fallback_models}（只在带 fallback beta 头时返回，SDK 3.3.0 的 {@code allowedFallbackModels()}）里各模型的窗口，取最小值；备用模型
 * 读不到时只用主模型的（T3）。
 */
public final class ClaudeWindow implements WindowSource {

    static final String FALLBACK_LIST_BETA = "server-side-fallback-2026-06-01";

    private final AnthropicClient client;
    private final String model;
    private final boolean fallbacks;
    private final RequestOptions options;

    public ClaudeWindow(AnthropicClient client, String model, boolean fallbacks, Duration timeout) {
        this.client = client;
        this.model = model;
        this.fallbacks = fallbacks;
        this.options = RequestOptions.builder().timeout(timeout).build();
    }

    @Override
    public OptionalLong read() {
        ModelRetrieveParams.Builder params = ModelRetrieveParams.builder();
        if (fallbacks) {
            params.addBeta(FALLBACK_LIST_BETA);
        }
        BetaModelInfo info = client.beta().models().retrieve(model, params.build(), options);
        Optional<Long> main = info.maxInputTokens().filter(tokens -> tokens > 0);
        if (main.isEmpty()) {
            return OptionalLong.empty();
        }
        long window = main.get();
        if (fallbacks) {
            for (String fallback : info.allowedFallbackModels().orElse(List.of())) {
                try {
                    Optional<Long> other = client.models().retrieve(fallback, com.anthropic.models.models.ModelRetrieveParams.none(),
                            options).maxInputTokens().filter(tokens -> tokens > 0);
                    if (other.isEmpty()) {
                        return OptionalLong.of(main.get());
                    }
                    window = Math.min(window, other.get());
                } catch (RuntimeException unreadable) {
                    return OptionalLong.of(main.get());
                }
            }
        }
        return OptionalLong.of(window);
    }
}
