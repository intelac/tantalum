package tantalum.value.model;

import java.util.Objects;
import tantalum.value.json.Json;

/**
 * 模型请求的一次工具调用。{@code rawArguments} 是模型发来的原文；解析失败时 {@code arguments} 为空，
 * 内核据此返回错误结果，不执行工具。
 */
public record ToolCallBlock(String id, String name, String rawArguments, Json arguments) implements ContentBlock {

    public ToolCallBlock {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rawArguments, "rawArguments");
    }

    public boolean argumentsValid() {
        return arguments != null;
    }
}
