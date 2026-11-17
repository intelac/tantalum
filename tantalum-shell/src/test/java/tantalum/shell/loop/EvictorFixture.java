package tantalum.shell.loop;

import java.time.Clock;
import static tantalum.shell.loop.Evictor.*;
import tantalum.shell.loop.Evictor.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class EvictorFixture {

    private EvictorFixture() {}

    public static Evictor of(Settings settings, Residency residency, Projections projections, Clock clock) {
        return new Evictor(settings, residency, projections, clock, session -> {});
    }
}
