package tantalum.local.tool;

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
import tantalum.local.tool.ToolArguments.Param;
import tantalum.value.json.Json;

/**
 * 读用户电脑上允许的目录里的文本文件。读到图片时（按文件头）把原始字节送回，由服务器规范化、存进附件再交给模型（同 pi
 * {@code read.ts:139-151}）；读到 PDF 时不把二进制当文字返回。附件在服务器上，改由服务器的 {@code read_attachment} 读。
 *
 * <p>文本按行窗口读取：边读边数，只把窗口里的行放进结果；默认最多 2000 行或 50 KB（pi
 * {@code truncate.ts:11-12}），没读完时结尾写明下一次的 {@code offset}（pi {@code read.ts:189-198}）。
 */
public final class ReadFile implements LocalTool {

    static final int MAX_LINES = 2000;
    static final int MAX_WINDOW_BYTES = 50 * 1024;
    static final int MAX_LINE_CHARS = 2000;
    static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    /** 图片送回服务器的上限：base64 之后还要放进 16 MB 的一帧（设计“Error Handling”）。 */
    static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    static final String LINE_TRUNCATED = "... [line truncated to " + MAX_LINE_CHARS + " characters]";
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Override public String name() { return "read_file"; }
    @Override public String description() {
        return "Read a UTF-8 text file in the allowed directories on the user's computer. path is relative to the main directory, "
                + "or absolute. Images are shown to you. Output is cut at " + MAX_LINES + " lines or " + MAX_WINDOW_BYTES / 1024
                + " KB, whichever comes first; for large files, read in parts with offset and limit, or find the place with grep first.";
    }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                Param.required("path", "string", "File path, relative to the main directory or absolute."),
                Param.optional("offset", "integer", "Line number to start from, 1-based. Default 1."),
                Param.optional("limit", "integer", "Maximum number of lines to return, at most " + MAX_LINES + ".")));
    }
    @Override public Kind kind() { return Kind.READ; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    /** 错误信息不带电脑上的绝对路径，只用模型给的路径。 */
    @Override
    public LocalResult run(LocalContext context, Json arguments) {
        String path = ToolArguments.string(arguments, "path");
        if (path == null) {
            return LocalResult.error("Missing argument: path");
        }
        try {
            Window window = Window.of(arguments);
            Path file = context.dirs().resolve(path);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return LocalResult.error("Not a file: " + path);
            }
            Optional<String> image = imageType(file);
            if (image.isPresent()) {
                return image(file, path, image.get());
            }
            Observations.Version before = Observations.Version.of(file);
            LocalResult output = text(file, path, window);
            // 读任何一个窗口都算读过：模型拿到续读说明，知道文件还有别的部分。读的过程中被改过就不记，下次改之前要重读
            if (!output.error() && before.equals(Observations.Version.of(file))) {
                context.observations().record(context.session(), file, before);
            }
            return output;
        } catch (IllegalArgumentException failure) {
            return LocalResult.error(failure.getMessage());
        } catch (CharacterCodingException failure) {
            return LocalResult.error("Not a UTF-8 text file: " + path);
        } catch (IOException failure) {
            return LocalResult.error("Cannot read " + path + ": " + failure.getClass().getSimpleName());
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

    /** 图片原样送回；太大的不送，免得一帧放不下。最多读上限加一个字节，读的时候文件变大也不会多占内存。 */
    private static LocalResult image(Path file, String path, String mime) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes((int) MAX_IMAGE_BYTES + 1);
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            return LocalResult.error("Image too large: " + path + " is over " + MAX_IMAGE_BYTES / (1024 * 1024) + " MB");
        }
        return LocalResult.image("Image " + path + " (" + mime + ", " + bytes.length + " bytes).", new LocalImage(mime, bytes));
    }

    /** 按文件头认 PNG、JPEG、GIF、WebP，同服务器上传附件的判断；其余（包括 SVG）返回空。 */
    static Optional<String> imageType(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            head = in.readNBytes(12);
        }
        if (startsWith(head, PNG)) {
            return Optional.of("image/png");
        }
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        if (ascii(head, 0, 6).equals("GIF87a") || ascii(head, 0, 6).equals("GIF89a")) {
            return Optional.of("image/gif");
        }
        if (ascii(head, 0, 4).equals("RIFF") && ascii(head, 8, 4).equals("WEBP")) {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    /**
     * 按 {@code offset}、{@code limit} 读一个文本文件的一个窗口，规则同 {@code read_file}（不查路径、不记先读后写）。
     * 服务器上的 {@code read_attachment} 用它读文本附件，两边的截断和续读说明是同一份代码。{@code path} 只用在说明和报错里。
     * 参数不对时抛 {@link IllegalArgumentException}，不是 UTF-8 时抛 {@link CharacterCodingException}。
     */
    public static LocalResult window(Path file, String path, Json arguments) throws IOException {
        return text(file, path, Window.of(arguments));
    }

    /** 先查大小和二进制，再边读边数：窗口里的行放进结果，其余只计数，所以 64 MB 的文件也不会整个进内存。 */
    private static LocalResult text(Path file, String path, Window window) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) {
            return LocalResult.error("File is larger than " + MAX_FILE_BYTES / (1024 * 1024) + " MB: " + path
                    + ". Use grep to find the lines you need, then read them with offset and limit.");
        }
        if (TextFiles.isBinary(file)) {
            return LocalResult.error(isPdf(file) ? "read_file cannot read PDF contents: " + path : "Binary file, not shown: " + path);
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
            return LocalResult.error("offset " + window.offset() + " is past the end of " + path + " (" + total + " lines)");
        }
        int last = window.offset() + count - 1;
        if (last < total) {
            shown.append("\n\n[Showing lines ").append(window.offset()).append('-').append(last).append(" of ").append(total)
                    .append(". Use offset=").append(last + 1).append(" to continue.]");
        }
        return LocalResult.ok(shown.toString());
    }

    private static boolean isPdf(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] head = in.readNBytes(PDF.length);
            return Arrays.equals(head, PDF);
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static String ascii(byte[] bytes, int from, int length) {
        return bytes.length < from + length ? "" : new String(bytes, from, length, StandardCharsets.ISO_8859_1);
    }
}
