package tantalum.memory;

import java.time.Clock;
import static tantalum.memory.DailyNotes.*;
import tantalum.memory.DailyNotes.*;
import tantalum.memory.ledger.Ledger;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class DailyNotesFixture {

    private DailyNotesFixture() {}

    public static DailyNotes of(java.nio.file.Path dir) {
        return new DailyNotes(Ledger.open(dir, Clock.systemUTC()));
    }
}
