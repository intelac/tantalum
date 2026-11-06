package tantalum.local.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import tantalum.local.tool.ToolArguments.Param;
import tantalum.value.json.Json;

/**
 * 新建或整个替换用户电脑上允许的目录里的文本文件（需求 2、C6）。覆盖已有文件前这个会话必须读过它且之后没被改过（C5）。
 * 建上级目录（pi {@code write.ts}；DSH {@code fsio.ts:630}），先写临时文件再改名（DSH {@code fsio.ts:618-702}），内容原样写入。
 */
public final class WriteFile implements LocalTool {

    static final int MAX_BYTES = 10 * 1024 * 1024;

    @Override public String name() { return "write_file"; }
    @Override public String description() {
        return "Create a UTF-8 text file in the allowed directories on the user's computer, or replace one completely. Parent "
                + "directories are created. To overwrite an existing file you must have read it with read_file in this session. "
                + "To change part of a file, use edit_file instead.";
    }
    @Override public Kind kind() { return Kind.WRITE; }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                Param.required("path", "string", "File path, relative to the main directory or absolute."),
                Param.required("content", "string", "The complete new content of the file, written as is.")));
    }

    @Override
    public LocalResult run(LocalContext context, Json arguments) {
        String path = ToolArguments.string(arguments, "path");
        String content = ToolArguments.string(arguments, "content");
        if (path == null) {
            return LocalResult.error("Missing argument: path");
        }
        if (content == null) {
            return LocalResult.error("Missing argument: content");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            return LocalResult.error("cannot write \"" + path + "\": content is larger than " + MAX_BYTES / (1024 * 1024) + " MB");
        }
        try {
            Path target = WritePaths.resolve(context.dirs(), path);
            Observations observations = context.observations();
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                return LocalResult.error("cannot write \"" + path + "\": it is a directory");
            }
            return observations.locked(target, () -> {
                boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
                if (existed) {
                    String refusal = WritePaths.refusal(observations.check(context.session(), target), path);
                    if (refusal != null) {
                        return LocalResult.error(refusal);
                    }
                }
                TextFiles.createParents(target);
                TextFiles.writeAtomically(target, bytes);
                observations.record(context.session(), target);
                return LocalResult.ok((existed ? "Updated " : "Created ") + path + " (" + bytes.length + " bytes).");
            });
        } catch (IllegalArgumentException failure) {
            return LocalResult.error(failure.getMessage());
        } catch (IOException failure) {
            return LocalResult.error("cannot write \"" + path + "\": " + failure.getClass().getSimpleName());
        }
    }
}
