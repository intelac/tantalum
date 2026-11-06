package tantalum.value.event;

import java.time.Instant;
import tantalum.value.id.CommandId;
import tantalum.value.json.Json;

/**
 * 插件工作完成。{@code continueText} 有值时，它作为用户消息追加，然后回到模型。
 * {@code startedAt} 是开始执行的时刻，没有真正执行时为 null；只供展示，内核不读。
 * {@code note} 是工作要求记在会话里的东西（一个对象，按字段并进 {@link tantalum.value.state.SessionState#notes()} 里这个工作类型的那一份），
 * 钩子以后从会话状态读它，比如“这个会话里一直允许”的工具；没有时为 null，不写出。失败的工作不记。
 */
public record WorkDone(CommandId command, String workType, Json result, boolean failed, String continueText, Instant startedAt,
                       Json.Obj note) implements ResultEvent {

    public static final String TYPE = "WorkDone";

    /** 不往会话里记东西的工作。 */
    public WorkDone(CommandId command, String workType, Json result, boolean failed, String continueText, Instant startedAt) {
        this(command, workType, result, failed, continueText, startedAt, null);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
