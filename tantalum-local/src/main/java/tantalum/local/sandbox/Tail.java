package tantalum.local.sandbox;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 命令输出只留末尾：最后 {@value #MAX_LINES} 行或 50 KB，先到哪个算哪个（截断照 pi {@code CA/src/core/tools/bash.ts:283}、
 * {@code truncate.ts:11-12}）。命令的结果和报错一般在最后，所以留尾不留头。内存只占上限的两倍，不随输出增长。
 */
public final class Tail {

    public static final int MAX_LINES = 2000;
    public static final int MAX_BYTES = 50 * 1024;

    /**
     * 留下的文字。{@code totalLines} 是全部输出的行数，{@code shownLines} 是留下的行数；{@code truncated} 时开头被截掉了。
     */
    public record Text(String text, boolean truncated, long totalLines, int shownLines) {}

    private final int maxLines;
    private final int maxBytes;
    private final byte[] buffer;
    private int length;
    private long total;
    private long newlines;
    private byte last;

    public Tail() {
        this(MAX_LINES, MAX_BYTES);
    }

    Tail(int maxLines, int maxBytes) {
        this.maxLines = maxLines;
        this.maxBytes = maxBytes;
        this.buffer = new byte[2 * maxBytes];
    }

    public synchronized void write(byte[] bytes, int offset, int count) {
        if (count <= 0) {
            return;
        }
        total += count;
        for (int i = offset; i < offset + count; i++) {
            if (bytes[i] == '\n') {
                newlines++;
            }
        }
        last = bytes[offset + count - 1];
        if (count > maxBytes) {
            offset += count - maxBytes;
            count = maxBytes;
            length = 0;
        }
        if (length + count > buffer.length) {
            System.arraycopy(buffer, length - maxBytes, buffer, 0, maxBytes);
            length = maxBytes;
        }
        System.arraycopy(bytes, offset, buffer, length, count);
        length += count;
    }

    public synchronized Text text() {
        long totalLines = newlines + (total > 0 && last != '\n' ? 1 : 0);
        int start = Math.max(0, length - maxBytes);
        byte[] kept = Arrays.copyOfRange(buffer, start, length);
        boolean truncated = total > kept.length;
        int from = 0;
        if (truncated) {
            // 截在一行中间时从下一行开始；整段都没有换行时只跳过半个 UTF-8 字符
            int newline = indexOf(kept, (byte) '\n');
            if (newline >= 0 && newline < kept.length - 1) {
                from = newline + 1;
            } else {
                while (from < kept.length && (kept[from] & 0xC0) == 0x80) {
                    from++;
                }
            }
        }
        String text = new String(kept, from, kept.length - from, StandardCharsets.UTF_8);
        if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.isEmpty()) {
            return new Text("", truncated, totalLines, 0);
        }
        String[] lines = text.split("\n", -1);
        if (lines.length > maxLines) {
            text = String.join("\n", Arrays.copyOfRange(lines, lines.length - maxLines, lines.length));
            return new Text(text, true, totalLines, maxLines);
        }
        return new Text(text, truncated, totalLines, lines.length);
    }

    private static int indexOf(byte[] bytes, byte wanted) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == wanted) {
                return i;
            }
        }
        return -1;
    }
}
