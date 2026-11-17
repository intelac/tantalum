package tantalum.memory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Function;
import static tantalum.memory.MemoryStores.*;
import tantalum.memory.MemoryStores.*;
import tantalum.value.id.Principal;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class MemoryStoresFixture {

    private MemoryStoresFixture() {}

    public static MemoryStores of(Function<Principal, Path> dirs, MemoryLimits limits, Clock clock) {
        return new MemoryStores(dirs, dirs, limits, clock);
    }
}
