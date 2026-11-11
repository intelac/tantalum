package tantalum.shell.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.wire.Context;
import tantalum.local.wire.DirState;
import tantalum.local.wire.SkillList;
import tantalum.local.wire.Wire;
import tantalum.shell.access.PluginSwitches;
import tantalum.shell.local.LocalLinks;
import tantalum.shell.local.LocalTokens;
import tantalum.shell.local.LocalWorkspace;
import tantalum.shell.local.TestComputer;
import tantalum.shell.param.ParamStore;
import tantalum.shell.param.SecretBox;
import tantalum.shell.plugin.Kind;
import tantalum.value.id.Principal;

/**
 * 向电脑要清单（skills-wiki 需求 2.5、3.7、6.6、8.2）：开关、连接、协议版本决定问哪几样；电脑没按时回就用这个用户上一次的，
 * 目录设置变过就不用。
 */
class SkillCatalogTest {

    private static final Principal ALICE = new Principal("alice");

    @TempDir
    Path temp;

    private LocalTokens tokens;
    private LocalLinks links;
    private PluginSwitches switches;
    private ParamStore params;
    private SkillSettings settings;
    private SkillCatalog catalog;
    private Path mac;

    @BeforeEach
    void setUp() throws Exception {
        tokens = LocalTokens.open(temp.resolve("data"), Clock.systemUTC());
        links = new LocalLinks(tokens, Clock.systemUTC(), new LocalLinks.Timing(Duration.ofSeconds(5), Duration.ofHours(1),
                Duration.ofHours(1), Duration.ofMillis(300)));
        switches = PluginSwitches.inMemory(Clock.systemUTC());
        params = ParamStore.inMemory(Clock.systemUTC(), new SecretBox(new byte[32]));
        settings = new SkillSettings(params);
        catalog = new SkillCatalog(new LocalWorkspace(switches, links, settings));
        mac = Files.createDirectories(temp.resolve("mac")).toRealPath();
        skill("pdf-tools", "Convert PDF files to text.");
        Files.createDirectories(mac.resolve("wiki"));
        Files.writeString(mac.resolve("wiki/deploy.md"), "# Deploying\n\nsteps");
        Files.writeString(mac.resolve("AGENTS.md"), "house rules");
    }

    @AfterEach
    void tearDown() throws Exception {
        links.close();
        tokens.close();
        switches.close();
        params.close();
    }

    private void skill(String name, String description) throws Exception {
        Path folder = Files.createDirectories(mac.resolve("skills").resolve(name));
        Files.writeString(folder.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: " + description + "\n---\nDo it.\n");
    }

    private TestComputer connect(int protocol) throws Exception {
        return TestComputer.connect(links, ALICE, Instant.now().plus(Duration.ofHours(1)), mac, false, false, protocol);
    }

    private void fillBoth() {
        settings.setSkillsDir(ALICE, mac.resolve("skills").toString());
        settings.setWikiDir(ALICE, mac.resolve("wiki").toString());
    }

    private static List<String> names(SkillList skills) {
        return skills.items().stream().map(SkillList.Item::name).toList();
    }

    @Test
    void oneRoundTripBringsAgentsSkillsAndWiki() throws Exception {
        fillBoth();
        try (TestComputer computer = connect(Wire.PROTOCOL)) {
            SkillCatalog.Listing listing = catalog.fetch(ALICE, null);
            assertEquals("house rules", listing.agents());
            assertEquals(List.of("pdf-tools"), names(listing.skills()));
            assertEquals(mac.resolve("skills").toString(), listing.skills().dir());
            assertEquals("deploy.md", listing.wiki().pages().getFirst().path());
            assertEquals("Deploying", listing.wiki().pages().getFirst().title());
            assertEquals(1, computer.contexts().size(), "one message, not three");
        }
    }

    @Test
    void withNothingFilledInOnlyAgentsIsAsked() throws Exception {
        try (TestComputer computer = connect(Wire.PROTOCOL)) {
            SkillCatalog.Listing listing = catalog.fetch(ALICE, null);
            assertEquals("house rules", listing.agents());
            assertNull(listing.skills());
            assertNull(listing.wiki());
            Context sent = computer.contexts().getFirst();
            assertNull(sent.skills());
            assertNull(sent.wiki());
        }
    }

    @Test
    void eachSwitchDecidesItsOwnDirectory() throws Exception {
        fillBoth();
        try (TestComputer computer = connect(Wire.PROTOCOL)) {
            switches.set(ALICE, Kind.LOCAL, "skills", false);
            SkillCatalog.Listing noSkills = catalog.fetch(ALICE, null);
            assertNull(noSkills.skills());
            assertNotNull(noSkills.wiki());
            assertNull(computer.contexts().getLast().skills(), "a switched-off directory is not even sent to the computer");

            switches.set(ALICE, Kind.LOCAL, "skills", true);
            switches.set(ALICE, Kind.LOCAL, "wiki", false);
            SkillCatalog.Listing noWiki = catalog.fetch(ALICE, null);
            assertNotNull(noWiki.skills());
            assertNull(noWiki.wiki());

            switches.set(ALICE, Kind.LOCAL, "local-workspace", false);
            int before = computer.contexts().size();
            assertEquals(new SkillCatalog.Listing(null, null, null), catalog.fetch(ALICE, null));
            assertEquals(before, computer.contexts().size(), "the master switch asks the computer nothing");
        }
    }

    /** 协议 3 的连接程序不认识新字段：不发给它，AGENTS.md 照旧（需求 2.5）。 */
    @Test
    void anOlderConnectorIsAskedOnlyForAgents() throws Exception {
        fillBoth();
        try (TestComputer computer = connect(3)) {
            SkillCatalog.Listing listing = catalog.fetch(ALICE, null);
            assertEquals("house rules", listing.agents());
            assertNull(listing.skills());
            assertNull(listing.wiki());
            assertNull(computer.contexts().getFirst().skills());
            assertNull(computer.contexts().getFirst().wiki());
        }
    }

    @Test
    void aDirectoryOutsideTheAllowedOnesComesBackAsOutside() throws Exception {
        settings.setSkillsDir(ALICE, temp.resolve("elsewhere").toString());
        try (TestComputer ignored = connect(Wire.PROTOCOL)) {
            SkillList skills = catalog.fetch(ALICE, null).skills();
            assertEquals(DirState.OUTSIDE, skills.state());
            assertEquals(List.of(), skills.items());
        }
    }

    /** 电脑没按时回：用上一次的清单，不能当成技能都删了（需求 3.7）。 */
    @Test
    void whenTheComputerIsLateTheLastListingIsUsed() throws Exception {
        fillBoth();
        try (TestComputer computer = connect(Wire.PROTOCOL)) {
            assertEquals(Optional.empty(), catalog.last(ALICE));
            catalog.fetch(ALICE, null);
            skill("deploy", "Ship the service.");
            computer.ignoreContextRequests();

            SkillCatalog.Listing late = catalog.fetch(ALICE, null);
            assertEquals(List.of("pdf-tools"), names(late.skills()), "the listing from before, not an empty one");
            assertEquals(1, late.wiki().pages().size());
            assertNull(late.agents(), "AGENTS.md is not kept: it is left out this once, as before");
            assertEquals(List.of("pdf-tools"), names(catalog.last(ALICE).orElseThrow().skills()));

            computer.answerContextRequests();
            assertEquals(List.of("deploy", "pdf-tools"), names(catalog.fetch(ALICE, null).skills()));
        }
    }

    @Test
    void theFirstTimeLateThereIsNoListing() throws Exception {
        fillBoth();
        try (TestComputer computer = connect(Wire.PROTOCOL)) {
            computer.ignoreContextRequests();
            assertEquals(new SkillCatalog.Listing(null, null, null), catalog.fetch(ALICE, null));
        }
    }

    /** 留下的清单是另一个目录的：改了目录又没回来时不拿旧目录的技能充数。 */
    @Test
    void aKeptListingIsNotUsedAfterTheDirectoryChanged() throws Exception {
        fillBoth();
        try (TestComputer computer = connect(Wire.PROTOCOL)) {
            catalog.fetch(ALICE, null);
            computer.ignoreContextRequests();
            settings.setSkillsDir(ALICE, mac.resolve("other-skills").toString());
            SkillCatalog.Listing late = catalog.fetch(ALICE, null);
            assertNull(late.skills());
            assertNotNull(late.wiki(), "the wiki directory did not change, so its listing still stands");

            settings.setSkillsDir(ALICE, mac.resolve("skills").toString());
            switches.set(ALICE, Kind.LOCAL, "wiki", false);
            SkillCatalog.Listing off = catalog.fetch(ALICE, null);
            assertNotNull(off.skills());
            assertNull(off.wiki(), "a switch turned off is not papered over by an old listing");
        }
    }

    @Test
    void withoutAComputerThereIsNothingEvenIfSomethingWasKept() throws Exception {
        fillBoth();
        try (TestComputer ignored = connect(Wire.PROTOCOL)) {
            catalog.fetch(ALICE, null);
        }
        assertEquals(new SkillCatalog.Listing(null, null, null), catalog.fetch(ALICE, null));
        assertTrue(catalog.last(ALICE).isPresent());
    }
}
