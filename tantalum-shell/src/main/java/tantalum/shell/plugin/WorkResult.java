package tantalum.shell.plugin;

import java.util.Objects;
import tantalum.value.json.Json;

/**
 * 工作的结果。{@code continueText} 有值时，它作为用户消息追加，然后回到模型。{@code note} 有值时按字段并进会话状态里这个工作类型的那一份
 * （{@code SessionState.note(工作类型)}），之后的钩子能读到；它随 {@code WorkDone} 记进 journal，恢复时照样还原。例如“这个会话里一直允许”的工具。
 */
public record WorkResult(Json result, String continueText, Json.Obj note) {

    public WorkResult {
        Objects.requireNonNull(result, "result");
    }

    /** 不往会话里记东西。 */
    public WorkResult(Json result, String continueText) {
        this(result, continueText, null);
    }
}
