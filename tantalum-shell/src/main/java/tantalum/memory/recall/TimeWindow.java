package tantalum.memory.recall;

import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问题里说的时间（memory-final-plan 第 9 节“时间”）：三天前、前天、昨天、今天、上周、N 天前，英文和日文的同样说法。
 * 给出要找的日子范围（含两头）；说法不准，前后各放宽一天。按用户的时区算日子由调用方给“今天”。
 */
public record TimeWindow(LocalDate from, LocalDate to) {

    /**
     * “N 天前”。数字最多四位（再长的不是天数）；中文数字写到九十九（十二、二十、二十三）。前面紧挨着“月”“号”的“N 日前”是日期
     * （“11月3日前完成”是截止日，不是三天前），不算。
     */
    private static final Pattern DAYS_AGO = Pattern.compile(
            "(?<![\\d月号])(\\d{1,4}|[一二两三四五六七八九十]{1,3})\\s*(?:天|日)前|(?<!\\d)(\\d{1,4})\\s*days?\\s+ago");
    private static final String NUMBERS = "零一二三四五六七八九";

    public static Optional<TimeWindow> in(String question, LocalDate today) {
        if (question == null) {
            return Optional.empty();
        }
        String text = question.toLowerCase(java.util.Locale.ROOT);
        Matcher days = DAYS_AGO.matcher(text);
        while (days.find()) {
            String number = days.group(1) != null ? days.group(1) : days.group(2);
            // “11月3日前”：数字前面隔着“月”
            if (days.group(1) != null && text.substring(0, days.start()).matches("[\\s\\S]*\\d\\s*月\\s*")) {
                continue;
            }
            int n = count(number);
            if (n >= 0) {
                return Optional.of(around(today.minusDays(n)));
            }
        }
        if (text.contains("前天") || text.contains("一昨日") || text.contains("day before yesterday")) {
            return Optional.of(around(today.minusDays(2)));
        }
        if (text.contains("昨天") || text.contains("昨日") || text.contains("yesterday")) {
            return Optional.of(around(today.minusDays(1)));
        }
        if (text.contains("上周") || text.contains("上星期") || text.contains("先週") || text.contains("last week")) {
            // 上周：上一个周一到周日，前后各放宽一天
            LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L).minusWeeks(1);
            return Optional.of(new TimeWindow(monday.minusDays(1), monday.plusDays(7)));
        }
        if (text.contains("今天") || text.contains("今日") || text.contains("today")) {
            return Optional.of(new TimeWindow(today, today));
        }
        return Optional.empty();
    }

    /** 阿拉伯数字，或者一到九十九的中文数字；读不出来是 -1。 */
    static int count(String number) {
        if (number.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(number);
        }
        String text = number.replace('两', '二');
        int ten = text.indexOf('十');
        if (ten < 0) {
            return text.length() == 1 ? NUMBERS.indexOf(text) : -1;
        }
        int tens = ten == 0 ? 1 : ten == 1 ? NUMBERS.indexOf(text.charAt(0)) : -1;
        int ones = ten == text.length() - 1 ? 0 : ten == text.length() - 2 ? NUMBERS.indexOf(text.charAt(text.length() - 1)) : -1;
        return tens < 1 || ones < 0 ? -1 : tens * 10 + ones;
    }

    private static TimeWindow around(LocalDate day) {
        return new TimeWindow(day.minusDays(1), day.plusDays(1));
    }

    public boolean contains(LocalDate day) {
        return !day.isBefore(from) && !day.isAfter(to);
    }
}
