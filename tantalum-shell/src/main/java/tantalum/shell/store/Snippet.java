package tantalum.shell.store;

import java.util.Locale;

/** 搜索结果里的一段原文：以命中处为中心，最多 {@code max} 个字（按码点算），换行变空格，截掉的一侧加省略号（session-find-pin R3）。 */
public final class Snippet {

    private static final String MORE = "…";

    private Snippet() {}

    public static String around(String text, String query, int max) {
        int[] points = text.replaceAll("\\s+", " ").strip().codePoints().toArray();
        String flat = new String(points, 0, points.length);
        int found = flat.toLowerCase(Locale.ROOT).indexOf(query.strip().toLowerCase(Locale.ROOT));
        int at = found < 0 ? 0 : flat.codePointCount(0, found);
        int length = query.strip().codePointCount(0, query.strip().length());
        if (points.length <= max) {
            return flat;
        }
        int start = Math.max(0, Math.min(at - (max - length) / 2, points.length - max));
        int end = Math.min(points.length, start + max);
        return (start > 0 ? MORE : "") + new String(points, start, end - start).strip() + (end < points.length ? MORE : "");
    }
}
