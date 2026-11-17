package tantalum.value.state;

import java.util.List;
import static tantalum.value.state.SessionContext.*;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.SessionContext.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class SessionContextFixture {

    private SessionContextFixture() {}

    public static SessionContext of(String system, List<ToolSpec> tools, CompactionSettings compaction, List<ContextSection> sections) {
        return new SessionContext(system, tools, compaction, sections, null, null, null, null, null);
    }
}
