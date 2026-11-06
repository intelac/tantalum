package tantalum.value.event;

import java.time.Instant;
import java.util.List;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.CommandId;

/**
 * 一次工具调用的结果，失败也算结果。{@code startedAt} 是拿到服务端名额、真正开始执行的时刻，没有真正执行时为 null；只供展示，内核不读。
 * {@code images} 是工具交给模型的图片（{@code read_file} 读到电脑上的图片、{@code read_attachment} 读到图片附件时），旧行读出来是空列表。
 */
public record ToolFinished(CommandId command, String toolCallId, String text, boolean error, Instant startedAt,
                           List<ImageRef> images) implements ResultEvent {

    public static final String TYPE = "ToolFinished";

    public ToolFinished {
        images = images == null ? List.of() : List.copyOf(images);
    }

    public ToolFinished(CommandId command, String toolCallId, String text, boolean error, Instant startedAt) {
        this(command, toolCallId, text, error, startedAt, List.of());
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
