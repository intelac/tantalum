package tantalum.shell.tool;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;

/** 读取工作目录内的文本文件。 */
public final class ReadFileTool implements Tool {

    private static final long MAX_BYTES = 1024 * 1024;
    private static final int WORKSPACE_CONCURRENCY = 16;

    @Override public String name() { return "read_file"; }
    @Override public String description() { return "Read a UTF-8 text file. path is relative to the workspace."; }
    @Override public Json schema() { return ToolArguments.schema("path"); }
    @Override public String server() { return "workspace"; }
    @Override public int serverConcurrency() { return WORKSPACE_CONCURRENCY; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        String path = ToolArguments.string(arguments, "path");
        if (path == null) {
            return ToolOutput.error("Missing argument: path");
        }
        try {
            Path file = context.workspace().resolve(path);
            if (!Files.isRegularFile(file)) {
                return ToolOutput.error("Not a file: " + path);
            }
            if (Files.size(file) > MAX_BYTES) {
                return ToolOutput.error("File is larger than " + MAX_BYTES + " bytes: " + path);
            }
            return ToolOutput.ok(Files.readString(file));
        } catch (IllegalArgumentException failure) {
            return ToolOutput.error(failure.getMessage());
        } catch (MalformedInputException failure) {
            return ToolOutput.error("Not a UTF-8 text file: " + path);
        } catch (IOException failure) {
            return ToolOutput.error("Cannot read " + path + ": " + failure.getMessage());
        }
    }
}
