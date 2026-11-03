package tantalum.value.model;

import java.util.Objects;
import tantalum.value.json.Json;

/**
 * 实际发出的请求选项。不含 system、工具、消息，不含任何请求头、地址和密钥。{@code extra} 是供应商特有的选项，键名和请求体里一致。
 * 只用于展示，内核不读。
 */
public record RequestOptions(String provider, String model, long maxTokens, boolean toolsAllowed, Json extra) {

    public RequestOptions {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(extra, "extra");
    }
}
