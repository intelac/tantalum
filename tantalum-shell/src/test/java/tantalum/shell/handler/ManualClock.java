package tantalum.shell.handler;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 测试里手动拨的时钟：处理器在哪一刻读时钟，看读到的值就知道。 */
final class ManualClock extends Clock {

    volatile Instant now;

    ManualClock(Instant now) {
        this.now = now;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
