package tantalum.shell.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tantalum.local.wire.Wire;
import tantalum.shell.access.PluginSwitches;
import tantalum.shell.local.LocalLinks;
import tantalum.shell.local.LocalTokens;
import tantalum.shell.local.LocalWorkspace;
import tantalum.shell.local.TestComputer;
import tantalum.shell.param.ParamStore;
import tantalum.shell.param.SecretBox;
import tantalum.shell.plugin.Kind;
import tantalum.value.event.SkillUse;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 提交时找用户点名的技能（skills-wiki 任务 18；需求 5.2–5.6、5.8、5.9）：哪些 {@code /name} 算候选；读到、没有这个技能、
 * 读坏了、电脑没按时回四种结果；开关、连接、协议、目录、关掉的名单决定问不问电脑。
 */
class SkillMentionsTest {

    private static final Principal ALICE = new Principal("alice");
    private static final SessionId SESSION = new SessionId("s-1");

    @TempDir
    Path temp;

    private LocalTokens tokens;
    private LocalLinks links;
    private PluginSwitches switches;
    private ParamStore params;
    private SkillSettings settings;
    private SkillCatalog catalog;
    private SkillMentions mentions;
    private Path mac;

    @BeforeEach
    void setUp() throws Exception {
        tokens = LocalTokens.open(temp.resolve("data"), Clock.systemUTC());
        links = new LocalLinks(tokens, Clock.systemUTC(), new LocalLinks.Timing(Duration.ofSeconds(5), Duration.ofHours(1),
                Duration.ofHours(1), Duration.ofMillis(300)));
        switches = PluginSwitches.inMemory(Clock.systemUTC());
        params = ParamStore.inMemory(Clock.systemUTC(), new SecretBox(new byte[32]));
        settings = new SkillSettings(params);
        LocalWorkspace workspace = new LocalWorkspace(switches, links, settings);
        catalog = new SkillCatalog(workspace);
        mentions = new SkillMentions(workspace, catalog);
        mac = Files.createDirectories(temp.resolve("mac")).toRealPath();
        skill("pdf-tools", "description: Convert PDF files to text.", "Use pdftotext.");
        skill("by-hand", "description: Only when asked.\ndisable-model-invocation: true", "Manual steps.");
        skill("model-only", "description: Not for users.\nuser-invocable: false", "x");
        settings.setSkillsDir(ALICE, mac.resolve("skills").toString());
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

    private TestComputer connect(int protocol) throws Exception {
        return TestComputer.connect(links, ALICE, Instant.now().plus(Duration.ofHours(1)), mac, false, false, protocol);
    }

    private static List<String> names(List<SkillUse> uses) {
        return uses.stream().map(SkillUse::name).toList();
    }

    /** 以空白为界的 /name 才是候选：路径、网址、大写、行内的斜杠都不算。 */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "/pdf-tools convert this            | pdf-tools",
        "please use /pdf-tools              | pdf-tools",
        "use /pdf-tools, then stop          | ''",
        "read /usr/local/bin and src/main   | ''",
        "see https://example.com/pdf-tools  | ''",
        "/PDF-Tools is not a name           | ''",
        "a/b and 1/2                        | ''",
        "/a /b /a /c                        | a,b,c",
        "/                                  | ''",
    })
    void onlyWhitespaceBoundedNamesAreCandidates(String text, String expected) {
        assertEquals(expected.isEmpty() ? List.of() : List.of(expected.split(",")), SkillMentions.candidates(text));
    }

    @Test
    void candidatesSpanLinesAndStopAtEight() {
        assertEquals(List.of("a", "b"), SkillMentions.candidates("first\n/a\n\tthen /b\n"));
        assertEquals(List.of("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8"),
                SkillMentions.candidates("/s1 /s2 /s3 /s4 /s5 /s6 /s7 /s8 /s9 /s10"));
        assertEquals(List.of(), SkillMentions.candidates("/" + "a".repeat(65)), "longer than a skill name can be");
    }

    @Test
    void namedSkillsAreReadInOrderOnceEach() throws Exception {
        try (TestComputer computer = connect(Wire.PROTOCOL)) {
            List<SkillUse> uses = mentions.read(ALICE, SESSION, "/by-hand first, then /pdf-tools and /by-hand again");
            assertEquals(List.of("by-hand", "pdf-tools"), names(uses));
            assertTrue(uses.get(0).content().startsWith("<skill_content name=\"by-hand\">"),
                    "a skill the model may not call can still be named by the user");
            assertTrue(uses.get(1).content().contains("Use pdftotext."));
            assertNull(uses.get(1).error());
            assertEquals(1, computer.loads().size(), "one message for all of them");
            assertEquals(List.of("by-hand", "pdf-tools"), computer.loads().getFirst().names());
            assertEquals("s-1", computer.loads().getFirst().session());
        }
    }

    /** 对不上技能的 /name 是普通文字；不让用户点名的也一样（需求 5.4、5.5）。 */
    @Test
    void namesThatAreNotSkillsAreJustText() throws Exception {
        try (TestComputer ignored = connect(Wire.PROTOCOL)) {
            assertEquals(List.of(), mentions.read(ALICE, SESSION, "/tmp is full and /model-only is hidden"));
            assertEquals(List.of("pdf-tools"), names(mentions.read(ALICE, SESSION, "/nope /pdf-tools")));
        }
    }

    /** 技能在但读坏了：照常发，记下原因（需求 5.8）。 */
    @Test
    void aBrokenSkillIsReportedWithItsCode() throws Exception {
        skill("broken", "description: [unclosed", "x");
        try (TestComputer ignored = connect(Wire.PROTOCOL)) {
            List<SkillUse> uses = mentions.read(ALICE, SESSION, "/broken go");
            assertEquals(List.of("broken"), names(uses));
            assertNull(uses.getFirst().content());
            assertEquals("description-missing", uses.getFirst().error());
        }
    }

    /** 电脑没按时回：上一次清单里能点名的记成“没读到，超时”，别的当普通文字（需求 5.8）。 */
    @Test
    void whenTheComputerIsLateKnownSkillsAreMarkedAsNotRead() throws Exception {
        try (TestComputer computer = connect(Wire.PROTOCOL)) {
            computer.ignoreContextRequests();
            assertEquals(List.of(), mentions.read(ALICE, SESSION, "/pdf-tools go"), "nothing is known yet, so it is just text");

            computer.answerContextRequests();
            catalog.fetch(ALICE, null);
            computer.ignoreContextRequests();
            List<SkillUse> uses = mentions.read(ALICE, SESSION, "/pdf-tools /tmp /model-only /by-hand");
            assertEquals(List.of("pdf-tools", "by-hand"), names(uses));
            assertTrue(uses.stream().allMatch(use -> use.content() == null && use.error().equals("timeout")));
        }
    }

    @Test
    void aSkillTheUserTurnedOffIsJustText() throws Exception {
        settings.setOff(ALICE, "pdf-tools", true);
        try (TestComputer computer = connect(Wire.PROTOCOL)) {
            assertEquals(List.of(), mentions.read(ALICE, SESSION, "/pdf-tools go"));
            assertEquals(List.of(), computer.loads(), "the computer is not asked about a skill that is off");
            assertEquals(List.of("by-hand"), names(mentions.read(ALICE, SESSION, "/pdf-tools /by-hand")));
            assertEquals(List.of("by-hand"), computer.loads().getFirst().names());
        }
    }

    /** “技能”关着、总开关关着、没填目录、连接程序太旧、没连上：都不问电脑，/name 是普通文字（需求 5.9、2.5）。 */
    @Test
    void withoutAUsableSkillsSetupNothingIsAsked() throws Exception {
        assertEquals(List.of(), mentions.read(ALICE, SESSION, "/pdf-tools go"), "not connected");
        try (TestComputer computer = connect(Wire.PROTOCOL)) {
            switches.set(ALICE, Kind.LOCAL, "skills", false);
            assertEquals(List.of(), mentions.read(ALICE, SESSION, "/pdf-tools go"));
            switches.set(ALICE, Kind.LOCAL, "skills", true);
            switches.set(ALICE, Kind.LOCAL, "local-workspace", false);
            assertEquals(List.of(), mentions.read(ALICE, SESSION, "/pdf-tools go"));
            switches.set(ALICE, Kind.LOCAL, "local-workspace", true);
            settings.setSkillsDir(ALICE, "");
            assertEquals(List.of(), mentions.read(ALICE, SESSION, "/pdf-tools go"));
            assertEquals(List.of(), mentions.read(new Principal("bob"), SESSION, "/pdf-tools go"), "someone else's computer");
            assertEquals(List.of(), computer.loads());
        }
        settings.setSkillsDir(ALICE, mac.resolve("skills").toString());
        try (TestComputer old = connect(3)) {
            assertEquals(List.of(), mentions.read(ALICE, SESSION, "/pdf-tools go"));
            assertEquals(List.of(), old.loads());
        }
    }
}
