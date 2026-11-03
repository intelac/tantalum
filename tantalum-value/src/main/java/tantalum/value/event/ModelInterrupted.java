package tantalum.value.event;

import java.time.Instant;
import tantalum.value.id.CommandId;
import tantalum.value.model.RequestOptions;

/** 回复被取消时已收到的文本。不改运行状态，只追加到会话消息。计时和选项同 {@link ModelReplied}。 */
public record ModelInterrupted(CommandId command, String text, Instant startedAt, Instant firstTokenAt, RequestOptions options)
        implements ResultEvent {

    public static final String TYPE = "ModelInterrupted";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
