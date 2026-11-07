package tantalum.shell.model;

import java.util.List;
import java.util.Map;
import tantalum.value.json.Json;

/**
 * OpenAI 兼容接口的思考参数怎么写。接口不报这个能力，由管理员按服务商文档给每条路线写一种；
 * 写法照 pi 的 {@code thinkingFormat}（{@code AI/src/api/openai-completions.ts:872-967}）。{@link #NONE} 不写任何参数，
 * 画面上也没有档位；{@link #REASONING_EFFORT} 的档位由路线配置；另外三种是开关，档位是 {@code off}、{@code on}。
 */
public enum OpenAiThinking {

    NONE("none"),
    REASONING_EFFORT("reasoning-effort"),
    ENABLE_THINKING("enable-thinking"),
    CHAT_TEMPLATE("chat-template"),
    THINKING_TYPE("thinking-type");

    public static final String OFF = "off";
    public static final String ON = "on";
    /** 开关式写法的档位，按这个顺序显示。 */
    public static final List<String> TOGGLE = List.of(OFF, ON);

    private final String configName;

    OpenAiThinking(String configName) {
        this.configName = configName;
    }

    /** 配置里的写法，如 {@code chat-template}。 */
    public String configName() {
        return configName;
    }

    /** 按配置里的写法找；不认识时抛出，消息写明可以写什么。 */
    public static OpenAiThinking parse(String name) {
        for (OpenAiThinking thinking : values()) {
            if (thinking.configName.equals(name)) {
                return thinking;
            }
        }
        throw new IllegalArgumentException("unknown thinking \"" + name + "\"; expected one of none, reasoning-effort, "
                + "enable-thinking, chat-template, thinking-type");
    }

    /** 开关式的写法。 */
    public boolean toggle() {
        return this == ENABLE_THINKING || this == CHAT_TEMPLATE || this == THINKING_TYPE;
    }

    /**
     * 这一档要加进请求体的字段，按顺序；{@code OpenAiClient} 把它们放进请求选项的 {@code extra}，请求体和轨迹页都从那里读。
     * {@link #NONE} 和 {@code level} 为 null（这个模型没有档位）时为空。开关式的写法只收 {@code on}、{@code off}，
     * 别的档位抛出，不就近换。
     */
    public Map<String, Json> fields(String level) {
        if (this == NONE || level == null) {
            return Map.of();
        }
        if (this == REASONING_EFFORT) {
            return Map.of("reasoning_effort", new Json.Str(level));
        }
        boolean on = switch (level) {
            case ON -> true;
            case OFF -> false;
            default -> throw new IllegalArgumentException(configName + " takes on or off, not " + level);
        };
        return switch (this) {
            case ENABLE_THINKING -> Map.of("enable_thinking", new Json.Bool(on));
            case CHAT_TEMPLATE -> Map.of("chat_template_kwargs", new Json.Obj(Map.of("enable_thinking", new Json.Bool(on))));
            case THINKING_TYPE -> Map.of("thinking", new Json.Obj(Map.of("type", new Json.Str(on ? "enabled" : "disabled"))));
            default -> throw new IllegalStateException(configName);
        };
    }
}
