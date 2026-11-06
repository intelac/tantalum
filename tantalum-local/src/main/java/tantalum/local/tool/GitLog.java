package tantalum.local.tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import tantalum.local.tool.ToolArguments.Param;
import tantalum.value.json.Json;

/** 列提交历史：一行一个提交，短哈希、日期、作者、标题。可以限定到一个文件或目录，从某个分支或提交往回看。 */
public final class GitLog implements LocalTool {

    static final int DEFAULT_COUNT = 20;
    static final int MAX_COUNT = 200;

    @Override public String name() { return "git_log"; }
    @Override public String description() {
        return "List commits of the Git repository on the user's computer, newest first, one per line: short hash, date, author, subject. "
                + "path limits the history to a file or directory (and picks the repository containing it). revision is the branch, "
                + "tag or commit to start from, or a range like main..feature. Default " + DEFAULT_COUNT + " commits, at most "
                + MAX_COUNT + ". Use git_show to see one commit.";
    }
    @Override public Kind kind() { return Kind.GIT; }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                Param.optional("path", "string", "File or directory, relative to the main directory or absolute. "
                        + "Default: the whole repository."),
                Param.optional("revision", "string", "Branch, tag, commit or range to list. Default: the current branch (HEAD)."),
                Param.optional("count", "integer", "Number of commits, at most " + MAX_COUNT + ". Default " + DEFAULT_COUNT + ".")));
    }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    @Override
    public LocalResult run(LocalContext context, Json arguments) throws IOException, InterruptedException {
        try {
            String revision = Git.revision(arguments, "revision");
            int count = Git.positive(arguments, "count", DEFAULT_COUNT, MAX_COUNT);
            Git.Place place = Git.place(context, ToolArguments.string(arguments, "path"));
            List<String> command = new ArrayList<>(List.of("log", "--no-color", "--date=short", "--format=%h %ad %an: %s",
                    "-n", String.valueOf(count)));
            if (revision != null) {
                command.add(revision);
            }
            command.add("--");
            if (place.target() != null) {
                command.add(place.target().toString());
            }
            return Git.result(place, command, "No commits.");
        } catch (IllegalArgumentException failure) {
            return LocalResult.error(failure.getMessage());
        }
    }
}
