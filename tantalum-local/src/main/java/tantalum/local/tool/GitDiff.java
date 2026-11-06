package tantalum.local.tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import tantalum.local.tool.ToolArguments.Param;
import tantalum.value.json.Json;

/**
 * 看没提交的改动，或者和某个提交比。用底层命令 {@code diff-files}、{@code diff-index}、{@code diff-tree}，不用 {@code git diff}：
 * 实测 {@code git diff} 即使设了 {@code GIT_OPTIONAL_LOCKS=0}，遇到 stat 过期的条目仍会改写 {@code .git/index}；底层命令从不写索引，
 * 输出的补丁格式相同。
 */
public final class GitDiff implements LocalTool {

    @Override public String name() { return "git_diff"; }
    @Override public String description() {
        return "Show changes in the Git repository on the user's computer as a unified diff. Default: changes not yet staged. "
                + "staged=true: changes staged for the next commit. revision: compare the working tree with that commit (HEAD shows "
                + "all uncommitted changes), or compare two commits with A..B. New untracked files are not included. Output is "
                + "cut at " + Git.MAX_LINES + " lines or " + Git.MAX_BYTES / 1024 + " KB; narrow it with path.";
    }
    @Override public Kind kind() { return Kind.GIT; }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                Param.optional("revision", "string", "Commit to compare with, or two commits A..B. Default: the index."),
                Param.optional("staged", "boolean", "Show staged changes (against revision, default HEAD). Default false."),
                Param.optional("path", "string", "Limit to this file or directory, relative to the main directory or absolute.")));
    }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    @Override
    public LocalResult run(LocalContext context, Json arguments) throws IOException, InterruptedException {
        try {
            String revision = Git.revision(arguments, "revision");
            boolean staged = ToolArguments.bool(arguments, "staged", false);
            List<String> command = command(revision, staged);
            Git.Place place = Git.place(context, ToolArguments.string(arguments, "path"));
            command.add("--");
            if (place.target() != null) {
                command.add(place.target().toString());
            }
            return Git.result(place, command, "No changes.");
        } catch (IllegalArgumentException failure) {
            return LocalResult.error(failure.getMessage());
        }
    }

    /** 四种比较各对应一个底层命令；{@code A..B} 拆成两个提交交给 {@code diff-tree}，空的一边是 HEAD。 */
    private static List<String> command(String revision, boolean staged) {
        List<String> command = new ArrayList<>();
        if (revision != null && revision.contains("..")) {
            if (revision.contains("...")) {
                throw new IllegalArgumentException("revision ranges with ... are not supported; use A..B");
            }
            if (staged) {
                throw new IllegalArgumentException("staged cannot be used with a range A..B");
            }
            int dots = revision.indexOf("..");
            String from = revision.substring(0, dots).isEmpty() ? "HEAD" : revision.substring(0, dots);
            String to = revision.substring(dots + 2).isEmpty() ? "HEAD" : revision.substring(dots + 2);
            if (to.startsWith("-")) {
                throw new IllegalArgumentException("revision must not start with -");
            }
            command.addAll(List.of("diff-tree", "-p", "-r"));
            command.addAll(Git.NO_EXTERNAL);
            command.addAll(List.of(from, to));
        } else if (staged) {
            command.addAll(List.of("diff-index", "-p", "--cached"));
            command.addAll(Git.NO_EXTERNAL);
            command.add(revision == null ? "HEAD" : revision);
        } else if (revision != null) {
            command.addAll(List.of("diff-index", "-p"));
            command.addAll(Git.NO_EXTERNAL);
            command.add(revision);
        } else {
            command.addAll(List.of("diff-files", "-p"));
            command.addAll(Git.NO_EXTERNAL);
        }
        return command;
    }
}
