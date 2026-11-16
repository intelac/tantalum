package tantalum.shell.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.Workspace;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/** {@code workspace_add}：模型把一个目录登记成工作区，规则和用户在侧栏点“添加”相同。这里电脑没连上，只查登记本身。 */
class WorkspaceAddToolTest {

    private static final Principal YI = new Principal("yi");

    @TempDir
    Path dir;

    private static Json args(String... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put(pairs[i], new Json.Str(pairs[i + 1]));
        }
        return new Json.Obj(fields);
    }

    @Test
    void addsTheFolderOnceAndSaysHowToUseIt() throws Exception {
        try (Projects projects = Projects.inMemory(Clock.systemUTC())) {
            WorkspaceAddTool tool = new WorkspaceAddTool(projects, (user, path) -> path.equals("/gone") ? WorkspaceAddTool.Folder.REFUSED
                    : path.equals("/seen") ? WorkspaceAddTool.Folder.OK : WorkspaceAddTool.Folder.UNCHECKED);
            ToolContext context = new ToolContext(YI, new SessionId("s"), new Workspace(dir));

            ToolOutput added = tool.run(context, args("path", " /home/yi/shop ", "title", "Shop"));
            assertFalse(added.error(), added.text());
            assertTrue(added.text().startsWith("Added workspace \"Shop\" at /home/yi/shop."), added.text());
            assertTrue(added.text().contains("not connected, so the folder was not checked"));
            assertFalse(added.text().contains("delegate"), "what the tool says back is repeated to the user: no tool names in it");
            assertEquals("Shop", projects.list(YI).getFirst().title());

            ToolOutput again = tool.run(context, args("path", "/home/yi/shop/"));
            assertFalse(again.error());
            assertEquals("This folder is already the workspace \"Shop\" (/home/yi/shop).", again.text());
            assertEquals(1, projects.list(YI).size());

            assertEquals("docs", projects.find(YI, idOf(projects, tool.run(context, args("path", "/home/yi/docs")))).orElseThrow().title(),
                    "without a title the folder's name is used");
        }
    }

    private static String idOf(Projects projects, ToolOutput output) throws Exception {
        assertFalse(output.error(), output.text());
        return projects.list(YI).stream().filter(project -> project.path().equals("/home/yi/docs")).findFirst().orElseThrow().id();
    }

    @Test
    void refusesWhatTheSidebarWouldRefuse() throws Exception {
        try (Projects projects = Projects.inMemory(Clock.systemUTC())) {
            WorkspaceAddTool tool = new WorkspaceAddTool(projects, (user, path) -> path.equals("/gone") ? WorkspaceAddTool.Folder.REFUSED
                    : path.equals("/seen") ? WorkspaceAddTool.Folder.OK : WorkspaceAddTool.Folder.UNCHECKED);
            ToolContext context = new ToolContext(YI, new SessionId("s"), new Workspace(dir));
            assertTrue(tool.run(context, args()).error());
            assertTrue(tool.run(context, args("path", "shop")).text().contains("absolute path"));
            tool.run(context, args("path", "/a", "title", "same"));
            ToolOutput taken = tool.run(context, args("path", "/b", "title", "same"));
            assertTrue(taken.error());
            assertTrue(taken.text().contains("give another title"));
            assertTrue(tool.run(context, args("path", "/c", "title", "x".repeat(Projects.MAX_TITLE + 1))).error());
            assertEquals(1, projects.list(YI).size());
            ToolOutput gone = tool.run(context, args("path", "/gone"));
            assertTrue(gone.error() && gone.text().contains("does not have this folder"), "the computer says no: not added");
            assertEquals(1, projects.list(YI).size());
            assertFalse(tool.run(context, args("path", "/seen")).text().contains("not checked"), "checked by the computer");
        }
    }
}
