package tantalum.shell.plugin;

import java.util.Objects;
import tantalum.value.json.Json;

/** 工作的结果。{@code continueText} 有值时，它作为用户消息追加，然后回到模型。 */
public record WorkResult(Json result, String continueText) {

    public WorkResult {
        Objects.requireNonNull(result, "result");
    }
}
