package tantalum.local.wire;

import static tantalum.local.wire.Context.*;
import tantalum.local.wire.Context.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class ContextFixture {

    private ContextFixture() {}

    public static Context of(String id) {
        return new Context(id, null, null, null);
    }

    public static Context of(String id, String dir) {
        return new Context(id, dir, null, null);
    }
}
