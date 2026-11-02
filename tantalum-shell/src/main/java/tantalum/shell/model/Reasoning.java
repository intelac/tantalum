package tantalum.shell.model;

import java.util.Optional;
import tantalum.value.json.Json;
import tantalum.value.model.ReasoningBlock;

/** 思考块的原文由各客户端写入：Claude 是带 {@code thinking} 字段的对象，OpenAI 兼容接口是字符串。 */
public final class Reasoning {

    private Reasoning() {}

    /** 能显示的思考文本；被供应商隐藏（加密的 redacted_thinking）时为空。 */
    public static Optional<String> displayText(ReasoningBlock reasoning) {
        return switch (reasoning.raw()) {
            case Json.Str text -> Optional.of(text.value());
            case Json.Obj object when object.fields().get("thinking") instanceof Json.Str text -> Optional.of(text.value());
            default -> Optional.empty();
        };
    }
}
