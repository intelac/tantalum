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
import java.util.List;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;

/** 读取工作目录内的文本文件。 */
public final class ReadFileTool implements Tool {

    static final int MAX_BYTES = 1024 * 1024;
    private static final int WORKSPACE_CONCURRENCY = 16;

    @Override public String name() { return "read_file"; }
    @Override public String description() { return "Read a UTF-8 text file. path is relative to the workspace."; }
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
            Path file = context.workspace().resolve(path);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return ToolOutput.error("Not a file: " + path);
            }
            byte[] bytes;
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) {
                return ToolOutput.error("File is larger than " + MAX_BYTES + " bytes: " + path);
            }
            return ToolOutput.ok(decodeUtf8(bytes));
        } catch (IllegalArgumentException failure) {
            return ToolOutput.error(failure.getMessage());
        } catch (CharacterCodingException failure) {
            return ToolOutput.error("Not a UTF-8 text file: " + path);
        } catch (IOException failure) {
            return ToolOutput.error("Cannot read " + path + ": " + failure.getClass().getSimpleName());
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }
}
