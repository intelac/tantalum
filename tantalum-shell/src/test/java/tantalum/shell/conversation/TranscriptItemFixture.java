package tantalum.shell.conversation;

import java.time.Instant;
import java.util.List;
import static tantalum.shell.conversation.TranscriptItem.*;
import tantalum.shell.conversation.TranscriptItem.*;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class TranscriptItemFixture {

    private TranscriptItemFixture() {}

    public static TranscriptItem.UserMessage userMessage(String id, SessionId session, RunId run, Instant at, String text, boolean queued) {
        return new TranscriptItem.UserMessage(id, session, run, at, text, List.of(), queued, false, List.of());
    }
}
