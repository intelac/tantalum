package tantalum.shell.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.AttachmentText;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.json.Json;

/**
 * 读取工作目录内的文本文件，以及本人的附件（{@code /attachments/sha256-<哈希>}，设计第 6 节）。读到图片时（按文件头，工作区里的
 * 图片也算）规范化、存进附件目录，把图片交给模型（同 pi {@code read.ts:139-151}）；读到 PDF 时不把二进制当文字返回。
 */
public final class ReadFileTool implements Tool {

    static final int MAX_BYTES = 1024 * 1024;
    /** 附件的只读路径前缀，和 {@link AttachmentRef#readonlyPath()} 一致。这个前缀只读：没有任何工具能往这里写。 */
    public static final String ATTACHMENTS = "/attachments/sha256-";
    private static final int WORKSPACE_CONCURRENCY = 16;
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};

    /** 为空时不认附件路径、不把图片交给模型（测试和不带附件目录的嵌入）。 */
    private final AttachmentStore attachments;

    public ReadFileTool() {
        this(null);
    }

    public ReadFileTool(AttachmentStore attachments) {
        this.attachments = attachments;
    }

    @Override public String name() { return "read_file"; }
    @Override public String description() {
        return "Read a UTF-8 text file. path is relative to the workspace, or an attachment's read-only path (/attachments/sha256-...). "
                + "Images are shown to you.";
    }
    @Override public Json schema() { return ToolArguments.schema(List.of("path"), List.of()); }
    @Override public String server() { return "workspace"; }
    @Override public int serverConcurrency() { return WORKSPACE_CONCURRENCY; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    /** 大小限制在读取时执行（只读到上限加一字节），不依赖先查再读；错误信息不带服务器上的绝对路径。 */
    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        String path = ToolArguments.string(arguments, "path");
        if (path == null) {
            return ToolOutput.error("Missing argument: path");
        }
        try {
            if (attachments != null && path.startsWith(ATTACHMENTS)) {
                return attachment(context, path);
            }
            Path file = context.workspace().resolve(path);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return ToolOutput.error("Not a file: " + path);
            }
            if (attachments != null && ImageNormalizer.sniff(file).isPresent()) {
                return workspaceImage(context, file, path);
            }
            byte[] bytes;
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readNBytes(MAX_BYTES + 1);
            }
            if (isPdf(bytes)) {
                return ToolOutput.error("read_file cannot read PDF contents: " + path);
            }
            return text(bytes, path);
        } catch (IllegalArgumentException failure) {
            return ToolOutput.error(failure.getMessage());
        } catch (IOException failure) {
            return ToolOutput.error("Cannot read " + path + ": " + failure.getClass().getSimpleName());
        }
    }

    /** 只在本人的附件目录里找；别人的哈希和不存在的一样。 */
    private ToolOutput attachment(ToolContext context, String path) throws IOException {
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
                if (file.bytes() > MAX_BYTES) {
                    yield ToolOutput.error("File is larger than " + MAX_BYTES + " bytes: " + path);
                }
                byte[] bytes = attachments.bytes(context.principal(), id);
                yield isPdf(bytes) ? ToolOutput.ok(pdfNote(file)) : text(bytes, path);
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

    private static ToolOutput text(byte[] bytes, String path) {
        if (bytes.length > MAX_BYTES) {
            return ToolOutput.error("File is larger than " + MAX_BYTES + " bytes: " + path);
        }
        try {
            return ToolOutput.ok(decodeUtf8(bytes));
        } catch (CharacterCodingException failure) {
            return ToolOutput.error("Not a UTF-8 text file: " + path);
        }
    }

    private static boolean isPdf(byte[] bytes) {
        return bytes.length >= PDF.length && Arrays.equals(bytes, 0, PDF.length, PDF, 0, PDF.length);
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }
}
