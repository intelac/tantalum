package tantalum.shell.tool;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.tool.ToolArguments.Param;
import tantalum.value.json.Json;

/**
 * 列一个目录的一层（需求 4、C9，照 pi {@code ls.ts:61,108}）：按名字排序不分大小写，目录名后加 {@code /}，包括以 {@code .} 开头的项。
 * 符号链接按它本身列出，不跟过去看是不是目录。
 */
public final class ListDirTool implements Tool {

    static final int MAX_ENTRIES = 500;
    private static final int WORKSPACE_CONCURRENCY = 16;

    @Override public String name() { return "list_dir"; }
    @Override public String description() {
        return "List the entries of a directory in the workspace, one level deep. Directories end with /. "
                + "To find files by name at any depth, use find_files.";
    }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                Param.optional("path", "string", "Directory relative to the workspace. Default: the workspace root.")));
    }
    @Override public String server() { return "workspace"; }
    @Override public int serverConcurrency() { return WORKSPACE_CONCURRENCY; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        String path = ToolArguments.string(arguments, "path");
        String shown = path == null ? "." : path;
        try {
            Path dir = context.workspace().resolve(shown);
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                return ToolOutput.error("Not a directory: " + shown);
            }
            List<String> names = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    names.add(Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) ? name + "/" : name);
                }
            }
            if (names.isEmpty()) {
                return ToolOutput.ok("(empty directory)");
            }
            names.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
            StringBuilder out = new StringBuilder(String.join("\n", names.subList(0, Math.min(names.size(), MAX_ENTRIES))));
            if (names.size() > MAX_ENTRIES) {
                out.append("\n\n[Showing ").append(MAX_ENTRIES).append(" of ").append(names.size()).append(" entries.]");
            }
            return ToolOutput.ok(out.toString());
        } catch (IllegalArgumentException failure) {
            return ToolOutput.error(failure.getMessage());
        } catch (IOException failure) {
            return ToolOutput.error("Cannot list " + shown + ": " + failure.getClass().getSimpleName());
        }
    }
}
