package tantalum.local.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import tantalum.local.tool.ToolArguments.Param;
import tantalum.value.json.Json;

/** 每一行最后是哪个提交改的。大文件先用行范围缩小。 */
public final class GitBlame implements LocalTool {

    @Override public String name() { return "git_blame"; }
    @Override public String description() {
        return "Show which commit last changed each line of a file in the Git repository on the user's computer: short hash, "
                + "author, date, line number and text. Use start_line and end_line for large files. revision blames the file as "
                + "it was in that commit.";
    }
    @Override public Kind kind() { return Kind.GIT; }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                Param.required("path", "string", "File, relative to the main directory or absolute."),
                Param.optional("start_line", "integer", "First line, 1-based. Default 1."),
                Param.optional("end_line", "integer", "Last line. Default: the end of the file."),
                Param.optional("revision", "string", "Commit to blame at. Default: the working tree.")));
    }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    @Override
    public LocalResult run(LocalContext context, Json arguments) throws IOException, InterruptedException {
        String path = ToolArguments.string(arguments, "path");
        if (path == null) {
            return LocalResult.error("Missing argument: path");
        }
        try {
            String revision = Git.revision(arguments, "revision");
            Integer start = ToolArguments.integer(arguments, "start_line");
            Integer end = ToolArguments.integer(arguments, "end_line");
            if (start != null && start < 1 || end != null && end < 1) {
                return LocalResult.error("start_line and end_line must be 1 or more");
            }
            if (start != null && end != null && end < start) {
                return LocalResult.error("end_line must not be before start_line");
            }
            Git.Place place = Git.place(context, path);
            if (Files.isDirectory(place.target())) {
                return LocalResult.error("Not a file: " + path);
            }
            List<String> command = new ArrayList<>(List.of("blame", "--date=short"));
            if (start != null || end != null) {
                command.addAll(List.of("-L", (start == null ? 1 : start) + "," + (end == null ? "" : end)));
            }
            if (revision != null) {
                command.add(revision);
            }
            command.addAll(List.of("--", place.target().toString()));
            return Git.result(place, command, "(empty file)");
        } catch (IllegalArgumentException failure) {
            return LocalResult.error(failure.getMessage());
        }
    }
}
