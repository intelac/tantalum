package tantalum.memory.capture;

import java.time.ZoneId;
import java.util.function.Function;
import java.util.function.Predicate;
import static tantalum.memory.capture.NoteTaker.*;
import tantalum.memory.DailyNotes;
import tantalum.memory.capture.NoteTaker.*;
import tantalum.value.id.Principal;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class NoteTakerFixture {

    private NoteTakerFixture() {}

    public static NoteTaker of(CaptureSettings settings, Predicate<Principal> userWants, Function<Principal, DailyNotes> notes, Function<Principal, NoteFiler> filers, Writer writer, Catchup catchup, ZoneId zone, long[] backoff) {
        return new NoteTaker(settings, userWants, notes, filers, writer, catchup, user -> zone, backoff);
    }
}
