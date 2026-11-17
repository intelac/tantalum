package tantalum.memory;

import java.nio.file.Path;
import java.time.Clock;
import static tantalum.memory.MemoryStore.*;
import tantalum.memory.MemoryStore.*;
import tantalum.memory.ledger.Ledger;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class MemoryStoreFixture {

    private MemoryStoreFixture() {}

    public static MemoryStore of(Path dir, MemoryLimits limits, Clock clock) {
        return new MemoryStore(Ledger.open(dir, clock), limits);
    }
}
