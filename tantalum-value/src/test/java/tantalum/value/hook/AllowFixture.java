package tantalum.value.hook;

import java.util.List;
import static tantalum.value.hook.Allow.*;
import tantalum.value.hook.Allow.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class AllowFixture {

    private AllowFixture() {}

    public static Allow of(PromptPatch patch) {
        return new Allow(patch, List.of());
    }
}
