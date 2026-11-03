package tantalum.value.event;

import java.time.Instant;
import tantalum.value.id.CommandId;
import tantalum.value.json.Json;

/**
 * 插件工作完成。{@code continueText} 有值时，它作为用户消息追加，然后回到模型。
 * {@code startedAt} 是开始执行的时刻，没有真正执行时为 null；只供展示，内核不读。
 */
public record WorkDone(CommandId command, String workType, Json result, boolean failed, String continueText, Instant startedAt)
        implements ResultEvent {

    public static final String TYPE = "WorkDone";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
