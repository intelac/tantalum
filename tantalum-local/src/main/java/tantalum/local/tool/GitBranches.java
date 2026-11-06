package tantalum.local.tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import tantalum.local.tool.ToolArguments.Param;
import tantalum.value.json.Json;

/** 列分支：当前分支前面有 {@code *}，每行带最新提交的短哈希、日期和标题。 */
public final class GitBranches implements LocalTool {

    @Override public String name() { return "git_branches"; }
    @Override public String description() {
        return "List branches of the Git repository on the user's computer. The current branch is marked with *; each line shows "
                + "the latest commit's short hash, date and subject. all=true also lists remote-tracking branches.";
    }
    @Override public Kind kind() { return Kind.GIT; }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                Param.optional("all", "boolean", "Also list remote-tracking branches. Default false."),
                Param.optional("path", "string", "A directory in the repository, relative to the main directory or absolute. "
                        + "Default: the main directory.")));
    }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    @Override
    public LocalResult run(LocalContext context, Json arguments) throws IOException, InterruptedException {
        try {
            boolean all = ToolArguments.bool(arguments, "all", false);
            Git.Place place = Git.place(context, ToolArguments.string(arguments, "path"));
            List<String> command = new ArrayList<>(List.of("branch", "--list", "--no-color",
                    "--format=%(HEAD) %(refname:short) %(objectname:short) %(committerdate:short) %(subject)"));
            if (all) {
                command.add("--all");
            }
            return Git.result(place, command, "No branches.");
        } catch (IllegalArgumentException failure) {
            return LocalResult.error(failure.getMessage());
        }
    }
}
