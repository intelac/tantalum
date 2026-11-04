package tantalum.value.model;

import java.util.Objects;
import tantalum.value.json.Json;

/**
 * 发给模型的工具定义。{@code deferred} 的工具定义照发、模型却看不见，直到历史里一条工具变化把它加进来（core-tools C14）；
 * 它只在投影出的 {@code Prompt} 里出现，journal 里的工具都不是 deferred。
 */
public record ToolSpec(String name, String description, Json schema, boolean deferred) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(schema, "schema");
    }

    public ToolSpec(String name, String description, Json schema) {
        this(name, description, schema, false);
    }

    public ToolSpec deferred(boolean value) {
        return value == deferred ? this : new ToolSpec(name, description, schema, value);
    }
}
