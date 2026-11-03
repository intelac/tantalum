package tantalum.shell.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.tool.ToolArguments.Param;
import tantalum.value.json.Json;

/**
 * 新建或整个替换工作目录里的文本文件（需求 2、C6）。覆盖已有文件前这个会话必须读过它且之后没被改过（C5）。
 * 建上级目录（pi {@code write.ts}；DSH {@code fsio.ts:630}），先写临时文件再改名（DSH {@code fsio.ts:618-702}），内容原样写入。
 */
public final class WriteFileTool implements Tool {

    static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int WORKSPACE_CONCURRENCY = 16;

    private final Observations observations;

    public WriteFileTool(Observations observations) {
        this.observations = observations;
    }

    @Override public String name() { return "write_file"; }
    @Override public String description() {
        return "Create a UTF-8 text file, or replace one completely. Parent directories are created. To overwrite an existing file "
                + "you must have read it with read_file in this session. To change part of a file, use edit_file instead.";
    }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                Param.required("path", "string", "File path relative to the workspace."),
                Param.required("content", "string", "The complete new content of the file, written as is.")));
    }
    @Override public String server() { return "workspace"; }
    @Override public int serverConcurrency() { return WORKSPACE_CONCURRENCY; }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        String path = ToolArguments.string(arguments, "path");
        String content = ToolArguments.string(arguments, "content");
        if (path == null) {
            return ToolOutput.error("Missing argument: path");
        }
        if (content == null) {
            return ToolOutput.error("Missing argument: content");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            return ToolOutput.error("cannot write \"" + path + "\": content is larger than " + MAX_BYTES / (1024 * 1024) + " MB");
        }
        try {
            Path target = WritePaths.resolve(context.workspace(), path);
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                return ToolOutput.error("cannot write \"" + path + "\": it is a directory");
            }
            return observations.locked(target, () -> {
                boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
                if (existed) {
                    String refusal = WritePaths.refusal(observations.check(context.principal(), context.session(), target), path);
                    if (refusal != null) {
                        return ToolOutput.error(refusal);
                    }
                }
                TextFiles.createParents(target);
                TextFiles.writeAtomically(target, bytes);
                observations.record(context.principal(), context.session(), target);
                return ToolOutput.ok((existed ? "Updated " : "Created ") + path + " (" + bytes.length + " bytes).");
            });
        } catch (IllegalArgumentException failure) {
            return ToolOutput.error(failure.getMessage());
        } catch (IOException failure) {
            return ToolOutput.error("cannot write \"" + path + "\": " + failure.getClass().getSimpleName());
        }
    }
}
