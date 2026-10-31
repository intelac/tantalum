package tantalum.value.model;

import java.util.Objects;
import tantalum.value.json.Json;

/** 发给模型的工具定义。 */
public record ToolSpec(String name, String description, Json schema) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(schema, "schema");
    }
}
