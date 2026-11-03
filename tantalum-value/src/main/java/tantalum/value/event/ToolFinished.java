package tantalum.value.event;

import java.time.Instant;
import tantalum.value.id.CommandId;

/** 一次工具调用的结果，失败也算结果。{@code startedAt} 是拿到服务端名额、真正开始执行的时刻，没有真正执行时为 null；只供展示，内核不读。 */
public record ToolFinished(CommandId command, String toolCallId, String text, boolean error, Instant startedAt) implements ResultEvent {

    public static final String TYPE = "ToolFinished";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
