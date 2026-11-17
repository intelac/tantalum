package tantalum.value.event;

import java.time.Instant;
import java.util.List;
import static tantalum.value.event.ToolFinished.*;
import tantalum.value.event.ToolFinished.*;
import tantalum.value.id.CommandId;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class ToolFinishedFixture {

    private ToolFinishedFixture() {}

    public static ToolFinished of(CommandId command, String toolCallId, String text, boolean error, Instant startedAt) {
        return new ToolFinished(command, toolCallId, text, error, startedAt, List.of());
    }
}
