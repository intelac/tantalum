package tantalum.local.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;
import tantalum.value.json.Json;

/**
 * 电脑上一个本机 MCP 服务器的现状（connector-jar 需求 6）：名字（{@code mcp.json} 里的键）、状态、出错原因、工具清单。
 * 工具的名字是 MCP 服务器自己的；服务器给模型时再加 {@code mcp__名字__} 前缀。
 *
 * @param reason 出错或停用的原因（英文，可能带 MCP 服务器 stderr 的末尾），别的状态为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpServerState(String name, State state, String reason, List<McpToolSpec> tools) {

    public enum State {
        /** 正在启动或重连。 */
        @com.fasterxml.jackson.annotation.JsonProperty("starting") STARTING,
        /** 好了，工具能用。 */
        @com.fasterxml.jackson.annotation.JsonProperty("ready") READY,
        /** 重连次数用完，工具撤掉了。 */
        @com.fasterxml.jackson.annotation.JsonProperty("failed") FAILED,
        /** {@code mcp.json} 里写了 {@code disabled}。 */
        @com.fasterxml.jackson.annotation.JsonProperty("disabled") DISABLED
    }

    public McpServerState {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(state, "state");
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** 一个工具：名字、说明、参数的 JSON Schema（原样）。 */
    public record McpToolSpec(String name, String description, Json inputSchema) {

        public McpToolSpec {
            Objects.requireNonNull(name, "name");
            description = description == null ? "" : description;
            inputSchema = inputSchema == null ? new Json.Obj(java.util.Map.of("type", new Json.Str("object"))) : inputSchema;
        }
    }
}
