package tantalum.local.wire;

import static tantalum.local.wire.Call.*;
import tantalum.local.wire.Call.*;
import tantalum.value.json.Json;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class CallFixture {

    private CallFixture() {}

    public static Call of(String id, String user, String session, String tool, Json arguments, long timeoutMs) {
        return new Call(id, user, session, tool, arguments, timeoutMs, null, null);
    }

    public static Call of(String id, String user, String session, String tool, Json arguments, long timeoutMs, String dir) {
        return new Call(id, user, session, tool, arguments, timeoutMs, dir, null);
    }
}
