package tantalum.shell.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import tantalum.shell.attachment.AttachmentRejected;
import tantalum.shell.attachment.AttachmentStore;
import tantalum.shell.attachment.ImageNormalizer;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.tool.ToolArguments.Param;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.AttachmentText;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.json.Json;

/**
 * 读取工作目录内的文本文件，以及本人的附件（{@code /attachments/sha256-<哈希>}，设计第 6 节）。读到图片时（按文件头，工作区里的
 * 图片也算）规范化、存进附件目录，把图片交给模型（同 pi {@code read.ts:139-151}）；读到 PDF 时不把二进制当文字返回。
 *
 * <p>文本按行窗口读取（core-tools 需求 1）：边读边数，只把窗口里的行放进结果；默认最多 2000 行或 50 KB（pi
 * {@code truncate.ts:11-12}），没读完时结尾写明下一次的 {@code offset}（pi {@code read.ts:189-198}）。
 */
public final class ReadFileTool implements Tool {

    static final int MAX_LINES = 2000;
    static final int MAX_WINDOW_BYTES = 50 * 1024;
    static final int MAX_LINE_CHARS = 2000;
    static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    static final String LINE_TRUNCATED = "... [line truncated to " + MAX_LINE_CHARS + " characters]";
    /** 附件的只读路径前缀，和 {@link AttachmentRef#readonlyPath()} 一致。这个前缀只读：没有任何工具能往这里写。 */
    public static final String ATTACHMENTS = "/attachments/sha256-";
    private static final int WORKSPACE_CONCURRENCY = 16;
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};

    /** 为空时不认附件路径、不把图片交给模型（测试和不带附件目录的嵌入）。 */
    private final AttachmentStore attachments;
    /** 为空时不记先读后写（测试和不带写工具的嵌入）。 */
    private final Observations observations;

    public ReadFileTool() {
        this(null, null);
    }

    public ReadFileTool(AttachmentStore attachments) {
        this(attachments, null);
    }

    public ReadFileTool(AttachmentStore attachments, Observations observations) {
        this.attachments = attachments;
        this.observations = observations;
    }

    @Override public String name() { return "read_file"; }
    @Override public String description() {
        return "Read a UTF-8 text file. path is relative to the workspace, or an attachment's read-only path (/attachments/sha256-...). "
                + "Images are shown to you. Output is cut at " + MAX_LINES + " lines or " + MAX_WINDOW_BYTES / 1024 + " KB, whichever "
                + "comes first; for large files, read in parts with offset and limit, or find the place with grep first.";
    }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                Param.required("path", "string", "File path relative to the workspace, or an attachment's read-only path."),
                Param.optional("offset", "integer", "Line number to start from, 1-based. Default 1."),
                Param.optional("limit", "integer", "Maximum number of lines to return, at most " + MAX_LINES + ".")));
    }
    @Override public String server() { return "workspace"; }
    @Override public int serverConcurrency() { return WORKSPACE_CONCURRENCY; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    /** 错误信息不带服务器上的绝对路径。 */
    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        String path = ToolArguments.string(arguments, "path");
        if (path == null) {
            return ToolOutput.error("Missing argument: path");
        }
        try {
            Window window = Window.of(arguments);
            if (attachments != null && path.startsWith(ATTACHMENTS)) {
                return attachment(context, path, window);
            }
            Path file = context.workspace().resolve(path);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return ToolOutput.error("Not a file: " + path);
            }
            if (attachments != null && ImageNormalizer.sniff(file).isPresent()) {
                return workspaceImage(context, file, path);
            }
            Observations.Version before = Observations.Version.of(file);
            ToolOutput output = text(file, path, window);
            // 读任何一个窗口都算读过（C5）：模型拿到续读说明，知道文件还有别的部分。读的过程中被改过就不记，下次改之前要重读
            if (observations != null && !output.error() && before.equals(Observations.Version.of(file))) {
                observations.record(context.principal(), context.session(), file, before);
            }
            return output;
        } catch (IllegalArgumentException failure) {
            return ToolOutput.error(failure.getMessage());
        } catch (CharacterCodingException failure) {
            return ToolOutput.error("Not a UTF-8 text file: " + path);
        } catch (IOException failure) {
            return ToolOutput.error("Cannot read " + path + ": " + failure.getClass().getSimpleName());
        }
    }

    /** 要读的行：从第 {@code offset} 行起最多 {@code limit} 行。 */
    private record Window(int offset, int limit) {

        static Window of(Json arguments) {
            Integer offset = ToolArguments.integer(arguments, "offset");
            Integer limit = ToolArguments.integer(arguments, "limit");
            if (offset != null && offset < 1) {
                throw new IllegalArgumentException("offset must be 1 or more");
            }
            if (limit != null && limit < 1) {
                throw new IllegalArgumentException("limit must be 1 or more");
            }
            return new Window(offset == null ? 1 : offset, limit == null ? MAX_LINES : Math.min(limit, MAX_LINES));
        }
    }

    /** 只在本人的附件目录里找；别人的哈希和不存在的一样。 */
    private ToolOutput attachment(ToolContext context, String path, Window window) throws IOException {
        String id = AttachmentRef.PREFIX + path.substring(ATTACHMENTS.length());
        Optional<AttachmentRef> found = attachments.ref(context.principal(), id, "sha256-" + id.substring(AttachmentRef.PREFIX.length(),
                Math.min(id.length(), AttachmentRef.PREFIX.length() + 8)));
        if (found.isEmpty()) {
            return ToolOutput.error("Not a file: " + path);
        }
        return switch (found.get()) {
            case ImageRef image -> ToolOutput.image(AttachmentText.image(image), image);
            case FileRef file when file.pdf() -> ToolOutput.ok(pdfNote(file));
            case FileRef file -> {
                Optional<AttachmentStore.Stored> stored = attachments.find(context.principal(), id);
                if (stored.isEmpty()) {
                    yield ToolOutput.error("Not a file: " + path);
                }
                yield isPdf(stored.get().file()) ? ToolOutput.ok(pdfNote(file)) : text(stored.get().file(), path, window);
            }
        };
    }

    /** 工作区里的图片：规范化、存进本人的附件目录（内容寻址，重复读不会重复存），把图片交给模型。 */
    private ToolOutput workspaceImage(ToolContext context, Path file, String path) throws IOException {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            AttachmentRef ref = attachments.upload(context.principal(), file.getFileName().toString(), null, in, Files.size(file));
            if (ref instanceof ImageRef image) {
                return ToolOutput.image(AttachmentText.image(image), image);
            }
            return ToolOutput.error("Not an image: " + path);
        } catch (AttachmentRejected rejected) {
            return ToolOutput.error("Cannot read image " + path + ": " + rejected.getMessage());
        }
    }

    private static String pdfNote(FileRef file) {
        return "\"" + file.name() + "\" is a PDF (" + file.pages() + " pages). read_file cannot read PDF contents; when it was attached "
                + "to a message, its pages were sent to you as a document.";
    }

    /** 先查大小和二进制，再边读边数：窗口里的行放进结果，其余只计数，所以 64 MB 的文件也不会整个进内存。 */
    private static ToolOutput text(Path file, String path, Window window) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) {
            return ToolOutput.error("File is larger than " + MAX_FILE_BYTES / (1024 * 1024) + " MB: " + path
                    + ". Use grep to find the lines you need, then read them with offset and limit.");
        }
        if (TextFiles.isBinary(file)) {
            return ToolOutput.error(isPdf(file) ? "read_file cannot read PDF contents: " + path : "Binary file, not shown: " + path);
        }
        StringBuilder shown = new StringBuilder();
        int bytes = 0;
        int count = 0;
        int total = 0;
        boolean full = false;
        try (TextFiles.LineReader lines = TextFiles.lines(file, MAX_LINE_CHARS)) {
            for (TextFiles.Line line = lines.next(); line != null; line = lines.next()) {
                total++;
                if (total < window.offset() || full) {
                    continue;
                }
                String rendered = line.truncated() ? line.text() + LINE_TRUNCATED : line.text();
                int size = rendered.getBytes(StandardCharsets.UTF_8).length + 1;
                if (count == window.limit() || bytes + size > MAX_WINDOW_BYTES) {
                    full = true;
                    continue;
                }
                if (count > 0) {
                    shown.append('\n');
                }
                shown.append(rendered);
                bytes += size;
                count++;
            }
        }
        if (window.offset() > Math.max(total, 1)) {
            return ToolOutput.error("offset " + window.offset() + " is past the end of " + path + " (" + total + " lines)");
        }
        int last = window.offset() + count - 1;
        if (last < total) {
            shown.append("\n\n[Showing lines ").append(window.offset()).append('-').append(last).append(" of ").append(total)
                    .append(". Use offset=").append(last + 1).append(" to continue.]");
        }
        return ToolOutput.ok(shown.toString());
    }

    private static boolean isPdf(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] head = in.readNBytes(PDF.length);
            return Arrays.equals(head, PDF);
        }
    }
}
