package tantalum.shell.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.tool.LocalTool.Kind;
import tantalum.local.wire.Call;
import tantalum.local.wire.SkillLoaded;
import tantalum.local.wire.Wire;
import tantalum.shell.access.PluginSwitches;
import tantalum.shell.local.LocalLinks.Reply;
import tantalum.shell.param.ParamStore;
import tantalum.shell.param.SecretBox;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.Workspace;
import tantalum.shell.skill.SkillSettings;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/**
 * {@code skill} 工具和点名在服务器这一侧（skills-wiki 需求 2.5、4.1、4.4、8.2、8.3、8.8）：什么时候算可用、调用带着技能目录、
 * 关掉的技能不发给电脑、旧连接程序收不到新消息。
 */
class LocalSkillTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");

    @TempDir
    Path temp;

    private LocalTokens tokens;
    private LocalLinks links;
    private PluginSwitches switches;
    private ParamStore params;
    private SkillSettings settings;
    private LocalWorkspace workspace;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private Path mac;

    @BeforeEach
    void setUp() throws Exception {
        tokens = LocalTokens.open(temp.resolve("data"), Clock.systemUTC());
        links = new LocalLinks(tokens, Clock.systemUTC(), new LocalLinks.Timing(Duration.ofSeconds(5), Duration.ofHours(1),
                Duration.ofHours(1), Duration.ofMillis(300)));
        switches = PluginSwitches.inMemory(Clock.systemUTC());
        params = ParamStore.inMemory(Clock.systemUTC(), new SecretBox(new byte[32]));
        settings = new SkillSettings(params);
        workspace = new LocalWorkspace(switches, links, settings);
        LocalTool.all(links, null, Duration.ofSeconds(60), settings).forEach(tool -> tools.put(tool.name(), tool));
        mac = Files.createDirectories(temp.resolve("mac")).toRealPath();
        skill("pdf-tools", "description: Convert PDF files to text.", "Use pdftotext.");
        skill("by-hand", "description: Only when asked.\ndisable-model-invocation: true", "Manual steps.");
        skill("broken", "description: [unclosed", "x");
    }

    @AfterEach
    void tearDown() throws Exception {
        links.close();
        tokens.close();
        switches.close();
        params.close();
    }

    private void skill(String name, String fields, String body) throws Exception {
        Path folder = Files.createDirectories(mac.resolve("skills").resolve(name));
        Files.writeString(folder.resolve("SKILL.md"), "---\nname: " + name + "\n" + fields + "\n---\n" + body + "\n");
    }

    private TestComputer connect(Principal user, int protocol) throws Exception {
        return TestComputer.connect(links, user, Instant.now().plus(Duration.ofHours(1)), mac, false, false, protocol);
    }

    private ToolOutput load(Principal who, String name) throws Exception {
        Path server = Files.createDirectories(temp.resolve("server").resolve(who.value()));
        return tools.get("skill").run(new ToolContext(who, new SessionId("s-1"), new Workspace(server)),
                new Json.Obj(Map.of("name", new Json.Str(name))));
    }

    /** 四样都满足才有 skill 工具：开关、连着、连接程序是协议 4、填了技能目录。没填目录的人工具列表和以前一样（需求 8.2、8.8）。 */
    @Test
    void theSkillToolNeedsTheSwitchAConnectorThatKnowsItAndADirectory() throws Exception {
        assertFalse(workspace.enabled(ALICE, Kind.SKILL), "not connected");
        try (TestComputer ignored = connect(ALICE, Wire.PROTOCOL)) {
            assertFalse(workspace.enabled(ALICE, Kind.SKILL), "no directory filled in");
            assertTrue(workspace.enabled(ALICE, Kind.READ));
            settings.setSkillsDir(ALICE, mac.resolve("skills").toString());
            assertTrue(workspace.enabled(ALICE, Kind.SKILL));
            assertFalse(workspace.enabled(BOB, Kind.SKILL), "alice's computer and directory give bob nothing");

            switches.set(ALICE, tantalum.shell.plugin.Kind.LOCAL, "skills", false);
            assertFalse(workspace.enabled(ALICE, Kind.SKILL));
            assertTrue(workspace.enabled(ALICE, Kind.READ), "the other tools do not follow the skills switch");
            switches.set(ALICE, tantalum.shell.plugin.Kind.LOCAL, "skills", true);
            switches.set(ALICE, tantalum.shell.plugin.Kind.LOCAL, "wiki", false);
            assertTrue(workspace.enabled(ALICE, Kind.SKILL), "the wiki switch has no tool of its own");
        }
        try (TestComputer ignored = connect(ALICE, 3)) {
            assertFalse(workspace.enabled(ALICE, Kind.SKILL), "a protocol 3 connector has no skill tool");
            assertTrue(workspace.enabled(ALICE, Kind.READ));
        }
    }

    @Test
    void aCallCarriesTheUsersSkillsDirectoryAndReturnsTheSkill() throws Exception {
        settings.setSkillsDir(ALICE, mac.resolve("skills").toString());
        try (TestComputer computer = connect(ALICE, Wire.PROTOCOL)) {
            ToolOutput output = load(ALICE, "pdf-tools");
            assertFalse(output.error(), output.text());
            assertEquals("<skill_content name=\"pdf-tools\">\n<skill_resources>\nBase directory for this skill: "
                    + mac.resolve("skills/pdf-tools") + "\nResolve relative paths mentioned by this skill against the base directory before"
                    + " using them. Load referenced resources only as needed.\n</skill_resources>\n\n<skill_instructions>\nUse pdftotext.\n"
                    + "</skill_instructions>\n</skill_content>", output.text());
            Call call = computer.nextCall(5);
            assertEquals("skill", call.tool());
            assertEquals(mac.resolve("skills").toString(), call.skills());
        }
    }

    @Test
    void otherToolsDoNotCarryTheSkillsDirectory() throws Exception {
        settings.setSkillsDir(ALICE, mac.resolve("skills").toString());
        try (TestComputer computer = connect(ALICE, Wire.PROTOCOL)) {
            tools.get("list_dir").run(new ToolContext(ALICE, new SessionId("s-1"), new Workspace(temp)), new Json.Obj(Map.of()));
            assertNull(computer.nextCall(5).skills());
        }
    }

    /** 三句错误原样到模型（需求 4.4）。 */
    @Test
    void theThreeErrorsReachTheModelWordForWord() throws Exception {
        settings.setSkillsDir(ALICE, mac.resolve("skills").toString());
        try (TestComputer ignored = connect(ALICE, Wire.PROTOCOL)) {
            ToolOutput invalid = load(ALICE, "Bad Name");
            assertTrue(invalid.error());
            assertEquals("Error: invalid skill name \"Bad Name\"", invalid.text());
            assertEquals("Error: skill \"nope\" is unknown or no longer available", load(ALICE, "nope").text());
            assertEquals("Error: skill \"broken\" is unknown or no longer available", load(ALICE, "broken").text());
            ToolOutput manual = load(ALICE, "by-hand");
            assertTrue(manual.error());
            assertEquals("Error: skill \"by-hand\" is not available for model invocation", manual.text());
        }
    }

    /** 关掉的技能不能加载，和不存在的说同一句话，也不发给电脑（需求 8.3）。 */
    @Test
    void aSkillTheUserTurnedOffIsNotLoadedOrForwarded() throws Exception {
        settings.setSkillsDir(ALICE, mac.resolve("skills").toString());
        settings.setOff(ALICE, "pdf-tools", true);
        try (TestComputer computer = connect(ALICE, Wire.PROTOCOL)) {
            ToolOutput output = load(ALICE, "pdf-tools");
            assertTrue(output.error());
            assertEquals("Error: skill \"pdf-tools\" is unknown or no longer available", output.text());
            assertNull(computer.nextCall(1), "the computer was not asked");

            settings.setOff(ALICE, "pdf-tools", false);
            assertFalse(load(ALICE, "pdf-tools").error());
        }
    }

    /** 没填目录硬调（工具本来就不会给模型）：电脑说没有这个技能。 */
    @Test
    void withoutADirectoryEverySkillIsUnknown() throws Exception {
        try (TestComputer ignored = connect(ALICE, Wire.PROTOCOL)) {
            assertEquals("Error: skill \"pdf-tools\" is unknown or no longer available", load(ALICE, "pdf-tools").text());
        }
    }

    /** 点名：读到的给内容，没有的和不让点名的两样都空，读坏的给代码。 */
    @Test
    void namedSkillsAreReadOnTheComputer() throws Exception {
        Files.writeString(mac.resolve("skills/pdf-tools/SKILL.md"),
                "---\nname: pdf-tools\ndescription: d\nuser-invocable: false\n---\nx\n");
        try (TestComputer computer = connect(ALICE, Wire.PROTOCOL)) {
            Reply<SkillLoaded> reply = links.skillLoad(ALICE, "s-1", mac.resolve("skills").toString(), List.of("by-hand", "nope", "pdf-tools"));
            assertEquals(Reply.Outcome.OK, reply.outcome());
            List<SkillLoaded.Loaded> items = reply.result().items();
            assertEquals(List.of("by-hand", "nope", "pdf-tools"), items.stream().map(SkillLoaded.Loaded::name).toList());
            assertTrue(items.get(0).content().startsWith("<skill_content name=\"by-hand\">"), "a manual-only skill can be named by the user");
            assertNull(items.get(1).content());
            assertNull(items.get(1).code());
            assertNull(items.get(2).content(), "not user-invocable: treated as plain text");
            assertEquals("s-1", computer.loads().getFirst().session());

            computer.ignoreContextRequests();
            assertEquals(Reply.Outcome.NO_ANSWER, links.skillLoad(ALICE, "s-1", mac.resolve("skills").toString(), List.of("by-hand")).outcome());
        }
        assertEquals(Reply.Outcome.NOT_CONNECTED, links.skillLoad(ALICE, "s-1", "/x", List.of("by-hand")).outcome());
    }

    /** 协议 3 的连接程序收到不认识的消息会断开：不发，调用也不带新字段（需求 2.5）。 */
    @Test
    void anOlderConnectorNeverSeesTheNewMessageOrFields() throws Exception {
        settings.setSkillsDir(ALICE, mac.resolve("skills").toString());
        try (TestComputer computer = connect(ALICE, 3)) {
            assertEquals(Reply.Outcome.NOT_CONNECTED, links.skillLoad(ALICE, "s-1", mac.resolve("skills").toString(), List.of("by-hand")).outcome());
            assertEquals(List.of(), computer.loads());
            assertTrue(links.status(ALICE).isPresent(), "the link is still up");

            load(ALICE, "pdf-tools");
            assertNull(computer.nextCall(5).skills());
            links.context(ALICE, null, mac.resolve("skills").toString(), mac.resolve("wiki").toString());
            assertNull(computer.contexts().getFirst().skills());
            assertNull(computer.contexts().getFirst().wiki());
        }
    }
}
