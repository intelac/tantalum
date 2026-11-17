package tantalum.shell.trace;

import java.util.List;
import static tantalum.shell.trace.TraceRecord.*;
import tantalum.shell.trace.TraceRecord.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class TraceRecordFixture {

    private TraceRecordFixture() {}

    public static TraceRecord.UserInput userInput(Head head, TraceRecord.UserInput.Mode mode, String text, Source source) {
        return new TraceRecord.UserInput(head, mode, text, source, List.of(), List.of());
    }
}
