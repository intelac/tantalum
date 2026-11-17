package tantalum.shell.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.browser.PageText;
import tantalum.local.tool.Browser;
import tantalum.local.tool.LocalImage;
import tantalum.local.tool.LocalResult;
import tantalum.local.wire.Hello;
import tantalum.local.wire.Wire;
import tantalum.shell.access.PluginGate;
import tantalum.shell.access.PluginSwitches;
import tantalum.shell.attachment.AttachmentLimits;
import tantalum.shell.attachment.AttachmentStore;
import tantalum.value.attachment.AttachmentText;
import tantalum.shell.attachment.ImageNormalizer;
import tantalum.shell.attachment.ToolImages;
import tantalum.shell.plugin.Kind;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.Workspace;
import tantalum.shell.tool.ToolCatalog;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.local.wire.HelloFixture;

/**
 * local-browser 需求 1.5、4.5、7.1、7.2、7.6：浏览器工具是转发到用户电脑的本机工具。什么时候可用、给模型的那句话、截图怎么进附件。
 * 电脑是测试用的假电脑，带一个记下调用的假浏览器；真浏览器在连接程序的 LocalBrowserIT。
 */
class LocalBrowserToolsTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final List<String> BROWSER = List.of("browser_click", "browser_evaluate", "browser_navigate", "browser_navigate_back",
            "browser_press_key", "browser_select_option", "browser_snapshot", "browser_tabs", "browser_take_screenshot", "browser_type",
            "browser_wait_for");

    @TempDir
    Path temp;

    private LocalTokens tokens;
    private LocalLinks links;
    private PluginSwitches switches;
    private PluginGate gate;
    private LocalWorkspace workspace;
    private AttachmentStore store;
    private Map<String, Tool> tools;
    private Path mac;
    /** 假浏览器收到的调用：会话、工具。 */
    private final List<String> seen = new ArrayList<>();
    private volatile LocalResult answer = LocalResult.ok(PageText.snapshot("Board", "https://board.example/", 1, 1, "- button \"Go\" [ref=e2]"));
    private final Browser fake = (session, tool, arguments) -> {
        seen.add(session + " " + tool);
        return answer;
    };

    @BeforeEach
    void setUp() throws Exception {
        tokens = LocalTokens.open(temp.resolve("data"), Clock.systemUTC());
        links = new LocalLinks(tokens, Clock.systemUTC(), LocalLinks.Timing.DEFAULT);
        switches = PluginSwitches.inMemory(Clock.systemUTC());
        AttachmentLimits limits = AttachmentLimits.defaults();
        store = new AttachmentStore(temp.resolve("data"), limits, new ImageNormalizer(limits));
        ToolCatalog catalog = new ToolCatalog();
        tools = new LinkedHashMap<>();
        for (Tool tool : LocalTool.all(links, new ToolImages(store), Duration.ofSeconds(60))) {
            catalog.register(tool);
            tools.put(tool.name(), tool);
        }
        workspace = LocalWorkspaceFixture.of(switches, links);
        gate = new PluginGate(catalog, switches, List.of(workspace), List.of(), List.of(), List.of(), java.util.Set.of());
        mac = Files.createDirectories(temp.resolve("mac"));
    }

    @AfterEach
    void tearDown() throws Exception {
        links.close();
        tokens.close();
        switches.close();
    }

    private TestComputer connect(Hello.Browser state, Browser browser) throws Exception {
        return TestComputer.connectWithBrowser(links, ALICE, Instant.now().plus(Duration.ofHours(1)), mac, state, browser);
    }

    private List<String> browserTools(Principal user) {
        return gate.enabledTools(user).stream().filter(name -> name.startsWith("browser_")).sorted().toList();
    }

    private ToolOutput run(String tool, Object... pairs) throws Exception {
        Map<String, Json> arguments = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            arguments.put((String) pairs[i], new Json.Str((String) pairs[i + 1]));
        }
        return tools.get(tool).run(new ToolContext(ALICE, new SessionId("s-1"), new Workspace(temp)), new Json.Obj(arguments));
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** qagent-align 需求 2.2、2.3、2.6：浏览器下面的工具能逐个去掉；被去掉的模型看不到，硬调被拒；开关关了再开，勾还在。 */
    @Test
    void browserToolsCanBePickedOneByOne() throws Exception {
        assertEquals(Map.of("browser", BROWSER.stream().sorted().toList()),
                Map.of("browser", workspace.choices().get("browser").stream().sorted().toList()), "only the browser has tools to pick");
        try (TestComputer ignored = connect(Hello.Browser.available("Google Chrome", false), fake)) {
            gate.set(ALICE, Kind.LOCAL, "browser_evaluate", false);
            gate.set(ALICE, Kind.LOCAL, "browser_tabs", false);
            List<String> left = BROWSER.stream().filter(name -> !name.equals("browser_evaluate") && !name.equals("browser_tabs")).toList();
            assertEquals(left, browserTools(ALICE));
            assertEquals(false, workspace.choices(ALICE).get("browser").get("browser_evaluate"));
            assertEquals(true, workspace.choices(ALICE).get("browser").get("browser_click"));
            assertEquals(workspace.choices().get("browser"), List.copyOf(workspace.choices(ALICE).get("browser").keySet()), "in the order they are defined");

            // 模型仍然调它：被拒，假浏览器没有收到
            ToolOutput refused = gate.runTool(new ToolContext(ALICE, new SessionId("s-1"), new Workspace(temp)),
                    tools.get("browser_evaluate"), new Json.Obj(Map.of("function", new Json.Str("() => 1"))));
            assertTrue(refused.error());
            assertEquals(PluginGate.TOOL_OFF, refused.text());
            assertEquals(List.of(), seen);

            // 浏览器关了再开：选择还在
            gate.set(ALICE, Kind.LOCAL, "browser", false);
            assertEquals(List.of(), browserTools(ALICE));
            gate.set(ALICE, Kind.LOCAL, "browser", true);
            assertEquals(left, browserTools(ALICE));

            // 恢复默认：勾回去
            gate.reset(ALICE, Kind.LOCAL, "browser_evaluate");
            gate.reset(ALICE, Kind.LOCAL, "browser_tabs");
            assertEquals(BROWSER, browserTools(ALICE));
        }
        // 别人不受影响；电脑没连上时选择照样能改
        gate.set(ALICE, Kind.LOCAL, "browser_click", false);
        assertEquals(true, workspace.choices(BOB).get("browser").get("browser_click"));
        assertEquals(false, workspace.choices(ALICE).get("browser").get("browser_click"));
    }

    /** 需求 2.8：一个都不勾，等于浏览器关着——工具没有，给模型的那一段里也不提浏览器。 */
    @Test
    void withEveryToolUntickedTheBrowserCountsAsOff() throws Exception {
        try (TestComputer ignored = connect(Hello.Browser.available("Google Chrome", false), fake)) {
            assertTrue(workspace.section(ALICE).orElseThrow().contains("Browser:"));
            for (String tool : BROWSER) {
                gate.set(ALICE, Kind.LOCAL, tool, false);
            }
            assertEquals(List.of(), browserTools(ALICE));
            assertFalse(workspace.section(ALICE).orElseThrow().contains("Browser:"));
            assertTrue(gate.enabledTools(ALICE).contains("read_file"));
        }
    }

    /** 需求 2.6：只有声明了可以逐个勾的才认；别的本机工具名照旧不是开关。 */
    @Test
    void onlyDeclaredToolsCanBePicked() {
        org.junit.jupiter.api.Assertions.assertThrows(PluginGate.UnknownPlugin.class, () -> gate.set(ALICE, Kind.LOCAL, "read_file", false));
        org.junit.jupiter.api.Assertions.assertThrows(PluginGate.UnknownPlugin.class, () -> gate.set(ALICE, Kind.TOOL, "browser_click", false));
        org.junit.jupiter.api.Assertions.assertThrows(PluginGate.UnknownPlugin.class, () -> gate.setDefault(Kind.LOCAL, "browser_click", false, true, ""));
    }

    /** 需求 7.2：总开关开、“浏览器”开、电脑连着、协议 5 起、连接程序说浏览器可用，五样都满足才有这 11 个工具。 */
    @Test
    void theBrowserToolsNeedTheSwitchesTheConnectionAndABrowserOnTheComputer() throws Exception {
        assertEquals(List.of(), browserTools(ALICE), "no computer connected");
        try (TestComputer ignored = connect(Hello.Browser.available("Google Chrome", false), fake)) {
            assertEquals(BROWSER, browserTools(ALICE));
            assertEquals(List.of(), browserTools(BOB), "alice's computer gives bob no browser");

            gate.set(ALICE, Kind.LOCAL, "browser", false);
            assertEquals(List.of(), browserTools(ALICE), "the browser switch is off");
            assertTrue(gate.enabledTools(ALICE).contains("read_file"), "the other local tools stay");
            gate.set(ALICE, Kind.LOCAL, "browser", true);
            gate.set(ALICE, Kind.LOCAL, "local-workspace", false);
            assertEquals(List.of(), browserTools(ALICE), "the master switch is off");
            gate.set(ALICE, Kind.LOCAL, "local-workspace", true);
            assertEquals(BROWSER, browserTools(ALICE), "the browser switch kept its state");
        }
        assertEquals(List.of(), browserTools(ALICE), "disconnected");
        for (Hello.Browser unusable : new Hello.Browser[] {Hello.Browser.unavailable(Hello.Browser.NOT_ALLOWED),
                Hello.Browser.unavailable(Hello.Browser.NOT_FOUND)}) {
            try (TestComputer ignored = connect(unusable, null)) {
                assertEquals(List.of(), browserTools(ALICE), unusable.code());
                assertTrue(gate.enabledTools(ALICE).contains("read_file"));
            }
        }
    }

    /** 需求 1.5：协议 4 及以前的连接程序没有浏览器这一项：别的工具照常，浏览器工具一个也没有。 */
    @Test
    void anOlderConnectorHasNoBrowser() throws Exception {
        try (TestComputer old = TestComputer.connect(links, ALICE, Instant.now().plus(Duration.ofHours(1)), mac, true, true, 4)) {
            assertEquals(null, old.hello().browser());
            assertEquals(List.of(), browserTools(ALICE));
            assertTrue(gate.enabledTools(ALICE).contains("skill") || gate.enabledTools(ALICE).contains("read_file"));
        }
        Hello four = new Hello(4, "t", new Hello.Device("m", "macos", "26.2", "arm64"), List.of(new Hello.Root("/w")), true,
                HelloFixture.shell(false, "no"), true, true, Hello.Browser.available("Google Chrome", false));
        assertFalse(LocalWorkspace.allows(four, tantalum.local.tool.LocalTool.Kind.BROWSER), "protocol 4 never has browser tools");
        Hello five = new Hello(Wire.PROTOCOL, "t", four.device(), four.roots(), true, four.shell(), true, true, four.browser());
        assertTrue(LocalWorkspace.allows(five, tantalum.local.tool.LocalTool.Kind.BROWSER));
        assertEquals("browser", LocalWorkspace.switchName(tantalum.local.tool.LocalTool.Kind.BROWSER));
    }

    /** 需求 7.6：浏览器可用时“本机工作区”那段多一句是哪个浏览器、有没有窗口；不可用时那一段和以前逐字相同。 */
    @Test
    void theSectionSaysWhichBrowserAndWhetherTheUserCanSeeIt() throws Exception {
        String without;
        try (TestComputer ignored = connect(Hello.Browser.unavailable(Hello.Browser.NOT_ALLOWED), null)) {
            without = workspace.section(ALICE).orElseThrow();
            assertFalse(without.contains("Browser"), without);
            assertTrue(without.endsWith("Shell: not available."), without);
        }
        try (TestComputer ignored = connect(Hello.Browser.available("Google Chrome", false), fake)) {
            assertEquals(without + " Browser: Google Chrome, window visible to the user.", workspace.section(ALICE).orElseThrow());
            gate.set(ALICE, Kind.LOCAL, "browser", false);
            assertEquals(without, workspace.section(ALICE).orElseThrow(), "switched off by the user: no sentence");
            gate.set(ALICE, Kind.LOCAL, "browser", true);
        }
        try (TestComputer ignored = connect(Hello.Browser.available("Microsoft Edge", true), fake)) {
            assertEquals(without + " Browser: Microsoft Edge, no visible window.", workspace.section(ALICE).orElseThrow());
        }
    }

    /** 调用带着会话转到电脑上的浏览器，结果原样回来（第一行是“外部网页内容不可信”）；参数不合格的在电脑上就被挡住。 */
    @Test
    void aCallIsForwardedToTheBrowserOnTheUsersComputer() throws Exception {
        try (TestComputer ignored = connect(Hello.Browser.available("Google Chrome", false), fake)) {
            ToolOutput opened = run("browser_navigate", "url", "https://board.example/");
            assertFalse(opened.error(), opened.text());
            assertEquals(PageText.UNTRUSTED, opened.text().lines().findFirst().orElseThrow());
            assertTrue(opened.text().contains("Page: Board"), opened.text());
            assertEquals(List.of("s-1 browser_navigate"), seen);

            ToolOutput file = run("browser_navigate", "url", "file:///etc/passwd");
            assertTrue(file.error());
            assertEquals("Only http and https URLs are allowed.", file.text());
            assertEquals(1, seen.size(), "the refused call never reached the browser");
        }
        ToolOutput offline = run("browser_snapshot");
        assertTrue(offline.error());
        assertEquals(LocalLinks.NOT_CONNECTED, offline.text());
    }

    /** 需求 1.1：电脑这边没允许（服务器硬发也没用）：电脑回那句拒绝。 */
    @Test
    void theComputerRefusesWhenItWasNotAllowed() throws Exception {
        try (TestComputer ignored = connect(Hello.Browser.unavailable(Hello.Browser.NOT_ALLOWED), null)) {
            ToolOutput refused = run("browser_snapshot");
            assertTrue(refused.error());
            assertEquals("This computer does not allow the browser.", refused.text());
        }
    }

    /**
     * 需求 4.5：截图从电脑送回来，存进本人的附件（别人看不到），结果是那句话、截的是哪个网址、再加图片引用那一行；
     * 附件名用电脑建议的，只取最后一段，电脑给的路径进不了附件名。
     */
    @Test
    void aScreenshotIsStoredInTheUsersAttachmentsUnderTheSuggestedName() throws Exception {
        try (TestComputer ignored = connect(Hello.Browser.available("Google Chrome", false), fake)) {
            answer = LocalResult.image(PageText.screenshot("https://board.example/"),
                    new LocalImage("image/png", png(), "screenshot-board.example.png"));
            ToolOutput shot = run("browser_take_screenshot");
            assertFalse(shot.error(), shot.text());
            ImageRef image = shot.images().getFirst();
            assertEquals("screenshot-board.example.png", image.name());
            assertEquals(PageText.UNTRUSTED + "\nScreenshot of https://board.example/\n" + AttachmentText.image(image), shot.text());
            assertTrue(store.find(ALICE, image.id()).isPresent());
            assertTrue(store.find(BOB, image.id()).isEmpty());

            answer = LocalResult.image(PageText.screenshot("https://x.example/"),
                    new LocalImage("image/png", png(), "../../etc/C:\\Users\\yi\\shot.png"));
            assertEquals("shot.png", run("browser_take_screenshot").images().getFirst().name());
            answer = LocalResult.image(PageText.screenshot("https://x.example/"), new LocalImage("image/png", png()));
            assertEquals("screenshot.png", run("browser_take_screenshot").images().getFirst().name(), "an older or odd connector sent no name");
        }
    }
}
