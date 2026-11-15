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

    private static final Pattern DAYS_AGO = Pattern.compile("(\\d+|[一二两三四五六七八九十])\\s*(?:天|日)前|(\\d+)\\s*days?\\s+ago");
    private static final String NUMBERS = "零一二三四五六七八九十";

    public static Optional<TimeWindow> in(String question, LocalDate today) {
        if (question == null) {
            return Optional.empty();
        }
        String text = question.toLowerCase(java.util.Locale.ROOT);
        Matcher days = DAYS_AGO.matcher(text);
        if (days.find()) {
            String number = days.group(1) != null ? days.group(1) : days.group(2);
            int n = number.chars().allMatch(Character::isDigit) ? Integer.parseInt(number)
                    : number.equals("两") ? 2 : NUMBERS.indexOf(number);
            return Optional.of(around(today.minusDays(n)));
        }
        if (text.contains("前天") || text.contains("一昨日") || text.contains("day before yesterday")) {
            return Optional.of(around(today.minusDays(2)));
        }
        if (text.contains("昨天") || text.contains("昨日") || text.contains("yesterday")) {
            return Optional.of(around(today.minusDays(1)));
        }
        if (text.contains("上周") || text.contains("上星期") || text.contains("先週") || text.contains("last week")) {
            return Optional.of(new TimeWindow(today.minusDays(14), today.minusDays(6)));
        }
        if (text.contains("今天") || text.contains("今日") || text.contains("today")) {
            return Optional.of(new TimeWindow(today, today));
        }
        return Optional.empty();
    }

    private static TimeWindow around(LocalDate day) {
        return new TimeWindow(day.minusDays(1), day.plusDays(1));
    }

    public boolean contains(LocalDate day) {
        return !day.isBefore(from) && !day.isAfter(to);
    }
}
