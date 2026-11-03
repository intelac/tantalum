package tantalum.shell.attachment;

import java.nio.charset.StandardCharsets;

/**
 * 上传时浏览器报的文件名只用来显示和下载，存储路径只由哈希决定（需求 2.5）。清洗后的名字不含路径、
 * 控制字符和 Windows 不允许的字符，UTF-8 不超过 255 字节。
 */
public final class FileNames {

    static final int MAX_BYTES = 255;

    private static final String WINDOWS_RESERVED = "<>:\"|?*";

    private FileNames() {}

    /** 清完为空时，图片叫 {@code image}，其余叫 {@code file}。 */
    public static String clean(String raw, boolean image) {
        String fallback = image ? "image" : "file";
        if (raw == null) {
            return fallback;
        }
        String name = lastSegment(raw);
        StringBuilder kept = new StringBuilder(name.length());
        name.codePoints().forEach(c -> {
            if (Character.isISOControl(c) || isBidiControl(c) || Character.getType(c) == Character.SURROGATE) {
                return;
            }
            kept.appendCodePoint(WINDOWS_RESERVED.indexOf(c) >= 0 ? '_' : c);
        });
        String cleaned = trim(truncate(trim(kept.toString())));
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private static String lastSegment(String raw) {
        int cut = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        return raw.substring(cut + 1);
    }

    /** 改写显示顺序的字符，能把 {@code txt.exe} 显示成像 {@code exe.txt} 的样子。 */
    private static boolean isBidiControl(int c) {
        return c >= 0x202A && c <= 0x202E || c >= 0x2066 && c <= 0x2069 || c == 0x200E || c == 0x200F;
    }

    /** 去掉首尾的空白和句点：Windows 会悄悄丢掉结尾的句点和空格，{@code ..} 这样的名字也没有意义。 */
    private static String trim(String name) {
        int start = 0;
        int end = name.length();
        while (start < end && isTrimmed(name.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmed(name.charAt(end - 1))) {
            end--;
        }
        return name.substring(start, end);
    }

    private static boolean isTrimmed(char c) {
        return c == '.' || Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** 按 UTF-8 字节截断，只在完整的字符之间截。 */
    private static String truncate(String name) {
        if (name.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) {
            return name;
        }
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < name.length(); ) {
            int c = name.codePointAt(i);
            int size = new String(Character.toChars(c)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > MAX_BYTES) {
                break;
            }
            out.appendCodePoint(c);
            bytes += size;
            i += Character.charCount(c);
        }
        return out.toString();
    }
}
