package tantalum.value.model;

import java.util.Objects;
import tantalum.value.json.Json;

/**
 * 模型的推理内容。{@code raw} 是供应商的原始内容，含签名，回传时必须原样发送；只回传给同一供应商。
 * {@code route}、{@code model} 是哪条路线、哪个模型写的，客户端据此决定这一次发不发；
 * 升级前的块没有，为 null。
 */
public record ReasoningBlock(String provider, Json raw, String route, String model) implements ContentBlock {

    public ReasoningBlock {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(raw, "raw");
    }

    public ReasoningBlock(String provider, Json raw) {
        this(provider, raw, null, null);
    }
}
