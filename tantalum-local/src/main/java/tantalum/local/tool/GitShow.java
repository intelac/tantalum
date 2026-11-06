package tantalum.local.tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import tantalum.local.tool.ToolArguments.Param;
import tantalum.value.json.Json;

/** 看一个提交：说明、改动统计和改动内容；或者用 {@code 提交:路径} 看某个文件当时的内容。 */
public final class GitShow implements LocalTool {

    @Override public String name() { return "git_show"; }
    @Override public String description() {
        return "Show one commit of the Git repository on the user's computer: message, changed files and the diff. path limits the "
                + "diff to a file or directory. To see a file as it was in a commit, use revision COMMIT:PATH with PATH relative to "
                + "the repository root, e.g. HEAD~2:src/App.java. Output is cut at " + Git.MAX_LINES + " lines or "
                + Git.MAX_BYTES / 1024 + " KB.";
    }
    @Override public Kind kind() { return Kind.GIT; }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                Param.required("revision", "string", "Commit, branch or tag to show, or COMMIT:PATH for a file at that commit."),
                Param.optional("path", "string", "Limit the diff to this file or directory, relative to the main directory or absolute.")));
    }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    @Override
    public LocalResult run(LocalContext context, Json arguments) throws IOException, InterruptedException {
        try {
            String revision = Git.revision(arguments, "revision");
            if (revision == null) {
                return LocalResult.error("Missing argument: revision");
            }
            Git.Place place = Git.place(context, ToolArguments.string(arguments, "path"));
            List<String> command = new ArrayList<>(List.of("show"));
            command.addAll(Git.NO_EXTERNAL);
            command.addAll(List.of("--stat", "--patch", revision, "--"));
            if (place.target() != null) {
                command.add(place.target().toString());
            }
            return Git.result(place, command, "(empty)");
        } catch (IllegalArgumentException failure) {
            return LocalResult.error(failure.getMessage());
        }
    }
}
