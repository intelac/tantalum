package tantalum.local.shell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 一条命令的输出（tool-scenarios-2 需求 6，Yi 2026-11-14：“返回正常和异常结果之后有可能被截断是个问题”）。总额度不变，仍是
 * {@value Tail#MAX_LINES} 行或 50 KB；不超时整段留下。超过时：
 * <ul>
 *   <li>留开头 {@value #HEAD_LINES} 行（最多 10 KB）和末尾其余的额度，中间写一行省掉了几行。出错信息常在最前面（编译错误、
 *       脚本第一行就失败），只留末尾会丢掉它。头尾两个窗口照 DSH {@code dsh-output-retention} 的 {@code TextRetainer}。</li>
 *   <li>完整输出写进 {@link Spill} 里的一个文件，照 DSH 笔记 {@code 2026-07-08-tool-output-spill-files}（{@code dsh-bash-local}
 *       在尾部溢出时把完整的 stdout/stderr 写进私有临时文件）。文件最大 {@link #FILE_MAX} 字节，到了就停，末尾写明。</li>
 * </ul>
 * 内存只占上限的几倍，不随输出增长。写的线程只有一个（读管道的那个），{@link #text} 在它结束后调用；方法都加了锁，以防万一。
 */
public final class Capture {

    public static final int HEAD_LINES = 200;
    public static final int HEAD_BYTES = 10 * 1024;
    /** 完整输出文件的上限：100 MB。 */
    public static final long FILE_MAX = 100L * 1024 * 1024;
    /** 文件到了上限时末尾写的一行。 */
    public static final String FILE_CUT = "\n[output file stopped here: it reached its size limit]\n";

    private final Spill spill;
    private final long fileMax;
    private final Tail tail = new Tail(Tail.MAX_LINES - HEAD_LINES, Tail.MAX_BYTES - HEAD_BYTES);
    /** 没超过上限之前的全部输出；超过以后不再增长。 */
    private final ByteArrayOutputStream early = new ByteArrayOutputStream();
    private long total;
    private long newlines;
    private byte last;
    private boolean over;
    private OutputStream file;
    private Path path;
    private long written;
    private boolean fileCut;

    /** {@code spill} 为 null 时不存完整输出（只留头尾）。 */
    public Capture(Spill spill) {
        this(spill, FILE_MAX);
    }

    Capture(Spill spill, long fileMax) {
        this.spill = spill;
        this.fileMax = fileMax;
    }

    public synchronized void write(byte[] bytes, int offset, int count) {
        if (count <= 0) {
            return;
        }
        tail.write(bytes, offset, count);
        total += count;
        for (int i = offset; i < offset + count; i++) {
            if (bytes[i] == '\n') {
                newlines++;
            }
        }
        last = bytes[offset + count - 1];
        if (over) {
            toFile(bytes, offset, count);
            return;
        }
        early.write(bytes, offset, count);
        if (early.size() > Tail.MAX_BYTES || newlines > Tail.MAX_LINES) {
            over = true;
            startFile();
        }
    }

    private void startFile() {
        if (spill == null) {
            return;
        }
        try {
            path = spill.newFile();
            file = Files.newOutputStream(path, java.nio.file.StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            byte[] kept = early.toByteArray();
            toFile(kept, 0, kept.length);
        } catch (IOException failure) {
            closeFile();
            path = null;
        }
    }

    private void toFile(byte[] bytes, int offset, int count) {
        if (file == null || fileCut) {
            return;
        }
        try {
            long room = fileMax - written;
            if (count > room) {
                file.write(bytes, offset, (int) room);
                file.write(FILE_CUT.getBytes(StandardCharsets.UTF_8));
                written = fileMax;
                fileCut = true;
                closeFile();
                return;
            }
            file.write(bytes, offset, count);
            written += count;
        } catch (IOException failure) {
            // 写不下去（磁盘满）：文件留着已写的部分，标成不完整
            fileCut = true;
            closeFile();
        }
    }

    private void closeFile() {
        if (file == null) {
            return;
        }
        try {
            file.close();
        } catch (IOException ignored) {
            // 关不上也不影响给模型的文字
        }
        file = null;
    }

    /** 留下的文字；同时关上完整输出的文件。 */
    public synchronized Tail.Text text() {
        closeFile();
        long totalLines = newlines + (total > 0 && last != '\n' ? 1 : 0);
        if (!over) {
            String whole = early.toString(StandardCharsets.UTF_8);
            if (whole.endsWith("\n")) {
                whole = whole.substring(0, whole.length() - 1);
            }
            int shown = whole.isEmpty() ? 0 : whole.split("\n", -1).length;
            return new Tail.Text(whole, false, totalLines, shown);
        }
        Tail.Text end = tail.text();
        byte[] start = early.toByteArray();
        int headEnd = 0;
        int headLines = 0;
        for (int i = 0; i < Math.min(start.length, HEAD_BYTES) && headLines < HEAD_LINES; i++) {
            if (start[i] == '\n') {
                headEnd = i + 1;
                headLines++;
            }
        }
        if (headLines == 0) {
            // 第一行就超过 10 KB：只留末尾，同以前
            return new Tail.Text(end.text(), true, totalLines, end.shownLines(), end.cutLine(), 0, path, fileCut);
        }
        String head = new String(start, 0, headEnd - 1, StandardCharsets.UTF_8);
        long omitted = Math.max(0, totalLines - headLines - end.shownLines());
        String text = head + "\n[... " + omitted + " lines omitted ...]\n" + end.text();
        return new Tail.Text(text, true, totalLines, headLines + end.shownLines(), end.cutLine(), headLines, path, fileCut);
    }
}
