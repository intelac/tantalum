package tantalum.shell.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.access.WebTool;
import tantalum.shell.boot.Engine;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.plugin.Tool;
import tantalum.shell.tool.ReadAttachmentTool;
import tantalum.shell.web.TestServer;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/**
 * 服务器上没有文件工具（local-workspace L6，需求 3.3）：启动完整的服务器，注册的工具只有四类——转发到电脑的 {@link LocalTool}、
 * 读本人附件的 {@code read_attachment}、只碰服务器自己数据的记忆工具、联网工具（web-tools：{@code web_fetch} 和 11 个浏览器工具，
 * 不碰文件由 ArchitectureTest {@code webToolsTouchNoFiles} 钉住）。以后有人再注册一个服务器上读写文件的工具，这里就会失败。
 */
class NoServerFileToolsTest {

    /** 只碰服务器自己的数据：附件目录（只读）、每人的记忆。 */
    private static final Set<String> SERVER_TOOLS = Set.of("read_attachment", "memory_read", "memory_write", "memory_delete");

    /** 联网工具：名字一个不多，包只能是 fetch 和 browser。 */
    private static final Set<String> WEB_TOOLS = Set.of("web_fetch", "browser_navigate", "browser_navigate_back", "browser_snapshot",
            "browser_click", "browser_type", "browser_press_key", "browser_select_option", "browser_wait_for", "browser_take_screenshot",
            "browser_tabs", "browser_evaluate");

    private static boolean web(Tool tool) {
        return tool instanceof WebTool;
    }

    @TempDir
    Path temp;

    @Test
    void everyRegisteredToolForwardsToTheComputerOrOnlyTouchesServerData() {
        try (TestServer server = new TestServer(temp.resolve("data"), temp.resolve("ws"),
                (prompt, chunks) -> new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null))) {
            List<Tool> tools = server.context.getBean(Engine.class).tools();
            for (Tool tool : tools) {
                if (tool instanceof LocalTool) {
                    continue;
                }
                if (web(tool)) {
                    assertTrue(WEB_TOOLS.contains(tool.name()), tool.name());
                    assertTrue(Set.of("tantalum.shell.fetch", "tantalum.shell.browser").contains(tool.getClass().getPackageName()),
                            tool.getClass().getName());
                    continue;
                }
                assertTrue(SERVER_TOOLS.contains(tool.name()), tool.name() + " (" + tool.getClass().getName() + ") runs on the server");
                assertTrue(tool instanceof ReadAttachmentTool || tool.getClass().getPackageName().equals("tantalum.shell.memory"),
                        tool.getClass().getName());
            }
            assertEquals(SERVER_TOOLS, Set.copyOf(tools.stream().filter(tool -> !(tool instanceof LocalTool) && !web(tool)).map(Tool::name).toList()));
            assertEquals(WEB_TOOLS, Set.copyOf(tools.stream().filter(NoServerFileToolsTest::web).map(Tool::name).toList()));
            List<String> forwarded = tools.stream().filter(LocalTool.class::isInstance).map(Tool::name).sorted().toList();
            assertEquals(tantalum.local.tool.Tools.all().stream().map(tantalum.local.tool.LocalTool::name).sorted().toList(), forwarded,
                    "every tool of the local workspace is a forwarding tool");
        }
    }
}
