package tantalum.shell.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import tantalum.local.tool.LocalResult;
import tantalum.local.tool.ReadFile;
import tantalum.shell.attachment.AttachmentStore;
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
 * 读本人的附件（{@code /attachments/sha256-<哈希>}，local-workspace L7）。这是服务器上唯一读文件的工具（Yi 2026-11-06 定），
 * 只读附件目录，不受本机工作区开关影响。代码从原来 {@code read_file} 的附件部分拆出来：图片交给模型看；PDF 只给说明；
 * 文本按行窗口读，规则和电脑上的 {@code read_file} 是同一份（{@link ReadFile#window}）。
 */
public final class ReadAttachmentTool implements Tool {

    /** 附件的只读路径前缀，和 {@link AttachmentRef#readonlyPath()} 一致。这个前缀只读：没有任何工具能往这里写。 */
    public static final String ATTACHMENTS = "/attachments/sha256-";
    private static final int CONCURRENCY = 16;
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};

    private final AttachmentStore attachments;

    public ReadAttachmentTool(AttachmentStore attachments) {
        this.attachments = attachments;
    }

    @Override public String name() { return "read_attachment"; }
    @Override public String description() {
        return "Read a file the user attached, by its read-only path (/attachments/sha256-...). Images are shown to you. "
                + "Text is cut at 2000 lines or 50 KB, whichever comes first; read in parts with offset and limit.";
    }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                Param.required("path", "string", "The attachment's read-only path, /attachments/sha256-..."),
                Param.optional("offset", "integer", "Line number to start from, 1-based. Default 1."),
                Param.optional("limit", "integer", "Maximum number of lines to return, at most 2000.")));
    }
    @Override public String server() { return "attachments"; }
    @Override public int serverConcurrency() { return CONCURRENCY; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    /** 只在本人的附件目录里找；别人的哈希、不存在的、不是附件路径的都是同一句话。错误信息不带服务器上的路径。 */
    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        String path = ToolArguments.string(arguments, "path");
        if (path == null) {
            return ToolOutput.error("Missing argument: path");
        }
        if (!path.startsWith(ATTACHMENTS)) {
            return ToolOutput.error("Not an attachment: " + path + ". Files on the user's computer are read with read_file.");
        }
        try {
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
                    yield isPdf(stored.get().file()) ? ToolOutput.ok(pdfNote(file)) : text(stored.get().file(), path, arguments);
                }
            };
        } catch (IllegalArgumentException failure) {
            return ToolOutput.error(failure.getMessage());
        } catch (CharacterCodingException failure) {
            return ToolOutput.error("Not a UTF-8 text file: " + path);
        } catch (IOException failure) {
            return ToolOutput.error("Cannot read " + path + ": " + failure.getClass().getSimpleName());
        }
    }

    private static ToolOutput text(Path file, String path, Json arguments) throws IOException {
        LocalResult result = ReadFile.window(file, path, arguments);
        return new ToolOutput(result.text(), result.error());
    }

    private static String pdfNote(FileRef file) {
        return "\"" + file.name() + "\" is a PDF (" + file.pages() + " pages). read_attachment cannot read PDF contents; when it was "
                + "attached to a message, its pages were sent to you as a document.";
    }

    private static boolean isPdf(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return Arrays.equals(in.readNBytes(PDF.length), PDF);
        }
    }
}
