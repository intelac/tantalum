package tantalum.value.model;

import java.util.Objects;
import tantalum.value.json.Json;

/** 模型的推理内容。{@code raw} 是供应商的原始内容，含签名，回传时必须原样发送；只回传给同一供应商。 */
public record ReasoningBlock(String provider, Json raw) implements ContentBlock {

    public ReasoningBlock {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(raw, "raw");
    }
}
