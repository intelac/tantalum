package tantalum.memory.recall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 问题里的时间词算成哪几天。今天是 2026-11-16，周一。 */
class TimeWindowTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    private static TimeWindow around(int daysAgo) {
        return new TimeWindow(TODAY.minusDays(daysAgo + 1L), TODAY.minusDays(daysAgo - 1L));
    }

    @Test
    void daysAgoReadsDigitsAndChineseNumbersUpToNinetyNine() {
        assertEquals(around(3), TimeWindow.in("三天前我们定了什么", TODAY).orElseThrow());
        assertEquals(around(2), TimeWindow.in("两天前呢", TODAY).orElseThrow());
        assertEquals(around(10), TimeWindow.in("十天前", TODAY).orElseThrow());
        assertEquals(around(12), TimeWindow.in("十二天前说的那件事", TODAY).orElseThrow());
        assertEquals(around(20), TimeWindow.in("二十天前", TODAY).orElseThrow());
        assertEquals(around(23), TimeWindow.in("二十三天前", TODAY).orElseThrow());
        assertEquals(around(15), TimeWindow.in("15 days ago", TODAY).orElseThrow());
        assertEquals(around(3), TimeWindow.in("3日前の話", TODAY).orElseThrow());
    }

    /** “11月3日前完成”是截止日期，不是三天前；长得不像天数的数字不当天数，也不抛异常。 */
    @Test
    void aDateOrAnOverlongNumberIsNotACountOfDays() {
        assertTrue(TimeWindow.in("11月3日前完成报告", TODAY).isEmpty());
        assertTrue(TimeWindow.in("11月 3日前完成报告", TODAY).isEmpty());
        assertTrue(TimeWindow.in("3号前交", TODAY).isEmpty());
        assertTrue(TimeWindow.in("99999999999 days ago", TODAY).isEmpty());
        assertTrue(TimeWindow.in("99999999999天前", TODAY).isEmpty());
    }

    /** 上周是上一个周一到周日（前后各放宽一天），不管今天星期几。 */
    @Test
    void lastWeekIsThePreviousMondayToSunday() {
        TimeWindow fromMonday = TimeWindow.in("上周做了什么", TODAY).orElseThrow();
        assertEquals(new TimeWindow(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 16)), fromMonday);
        assertTrue(fromMonday.contains(LocalDate.of(2026, 11, 9)) && fromMonday.contains(LocalDate.of(2026, 11, 15)));
        TimeWindow fromFriday = TimeWindow.in("what did we do last week", LocalDate.of(2026, 11, 20)).orElseThrow();
        assertEquals(new TimeWindow(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 16)), fromFriday);
    }
}
