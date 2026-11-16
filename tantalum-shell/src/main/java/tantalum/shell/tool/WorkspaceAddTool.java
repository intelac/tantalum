package tantalum.shell.tool;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.shell.local.LocalLinks;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolArguments;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolDispatch;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.project.Project;
import tantalum.shell.project.ProjectPaths;
import tantalum.shell.project.Projects;
import tantalum.value.json.Json;

/**
 * {@code workspace_add}：把用户电脑上的一个目录登记成工作区（名字加目录），和用户在侧栏点“添加”是同一件事、同一套规则。
 * 登记以后可以用 {@code delegate} 的 {@code workspace} 在那里开会话。执行前先问用户（{@code WorkspaceApprovalHook}），用户不同意就不登记。电脑连着时先确认目录存在、允许；没连上照样登记，结果里写明没确认。
 * 它只登记，不在电脑上建目录：还没有的目录要先建好。
 */
public final class WorkspaceAddTool implements Tool {

    public static final String NAME = "workspace_add";

    static final ToolDispatch DISPATCH = ToolDispatch.exclusive(NAME, "workspaces", 4);

    private final Projects projects;
    private final LocalLinks links;

    public WorkspaceAddTool(Projects projects, LocalLinks links) {
        this.projects = projects;
        this.links = links;
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Add a folder on the user's computer as a workspace, so conversations can be opened to work in it (delegate with "
                + "workspace=<path>). path: the folder's absolute path; it must already exist. title: a short name; the folder's "
                + "name is used when omitted. The user is asked to confirm before it is added. The existing workspaces are listed "
                + "in the workspaces section.";
    }

    @Override public Json schema() {
        Map<String, Json> properties = new LinkedHashMap<>();
        properties.put("path", property("Absolute path of an existing folder on the user's computer."));
        properties.put("title", property("Short name for the workspace. Defaults to the folder's name."));
        Map<String, Json> schema = new LinkedHashMap<>();
        schema.put("type", new Json.Str("object"));
        schema.put("properties", new Json.Obj(properties));
        schema.put("required", new Json.Arr(List.of(new Json.Str("path"))));
        schema.put("additionalProperties", new Json.Bool(false));
        return new Json.Obj(schema);
    }

    private static Json property(String description) {
        Map<String, Json> property = new LinkedHashMap<>();
        property.put("type", new Json.Str("string"));
        property.put("description", new Json.Str(description));
        return new Json.Obj(property);
    }

    @Override public String server() { return DISPATCH.server(); }
    @Override public ToolDispatch dispatch() { return DISPATCH; }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) throws InterruptedException {
        String path = ToolArguments.string(arguments, "path");
        if (path == null || path.isBlank()) {
            return ToolOutput.error("Missing argument: path");
        }
        path = path.strip();
        if (!ProjectPaths.isAbsolute(path)) {
            return ToolOutput.error("path must be an absolute path on the user's computer, like /home/me/project or C:\\Work\\project.");
        }
        // 电脑连着、认得列目录时先问一次：不存在或不允许就不登记
        LocalLinks.Listing listing = links.browse(context.principal(), path);
        boolean checked = listing.outcome() == LocalLinks.Listing.Outcome.OK;
        if (checked && listing.result().error() != null) {
            return ToolOutput.error("The computer does not have this folder or does not allow it: " + path
                    + ". Create it first, or choose a folder inside the allowed ones.");
        }
        try {
            String key = ProjectPaths.key(path);
            for (Project existing : projects.list(context.principal())) {
                if (ProjectPaths.key(existing.path()).equals(key)) {
                    return ToolOutput.ok("This folder is already the workspace \"" + existing.title() + "\" (" + existing.path() + ").");
                }
            }
            Project added = projects.add(context.principal(), path, ToolArguments.string(arguments, "title"));
            return ToolOutput.ok("Added workspace \"" + added.title() + "\" at " + added.path() + "."
                    + (checked ? "" : " The computer is not connected, so the folder was not checked.")
                    + " Open a conversation in it with delegate (workspace=\"" + added.path() + "\").");
        } catch (Projects.Refused refused) {
            return ToolOutput.error(switch (refused.problem()) {
                case PATH_INVALID -> "path must be an absolute path.";
                case PATH_TAKEN -> "This folder is already a workspace.";
                case TITLE_INVALID -> "title must be 1 to " + Projects.MAX_TITLE + " characters.";
                case TITLE_TAKEN -> "A workspace with this title exists; give another title.";
                case NOT_FOUND -> "No such workspace.";
            });
        } catch (SQLException failure) {
            return ToolOutput.error("The workspaces cannot be saved right now.");
        }
    }
}
