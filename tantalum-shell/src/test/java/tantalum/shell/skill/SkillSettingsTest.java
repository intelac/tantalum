package tantalum.shell.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tantalum.shell.param.ParamStore;
import tantalum.shell.param.SecretBox;
import tantalum.value.id.Principal;

/** 技能目录、wiki 目录和关掉的技能（skills-wiki 需求 2.1、6.1、8.3、8.7）：每个用户自己的一份，空串清掉，写法不对不存。 */
class SkillSettingsTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");

    private ParamStore params;
    private SkillSettings settings;

    @BeforeEach
    void open() throws Exception {
        params = ParamStore.inMemory(Clock.systemUTC(), new SecretBox(new byte[32]));
        settings = new SkillSettings(params);
    }

    @AfterEach
    void close() throws Exception {
        params.close();
    }

    @Test
    void nothingIsSetByDefault() {
        assertEquals(Optional.empty(), settings.skillsDir(ALICE));
        assertEquals(Optional.empty(), settings.wikiDir(ALICE));
        assertEquals(Set.of(), settings.off(ALICE));
    }

    @Test
    void eachUserKeepsTheirOwnDirectoriesAndAnEmptyStringClearsThem() {
        settings.setSkillsDir(ALICE, "  ~/.agents/skills ");
        settings.setWikiDir(ALICE, "/Users/alice/wiki");
        assertEquals(Optional.of("~/.agents/skills"), settings.skillsDir(ALICE), "stored trimmed, ~ left for the computer to expand");
        assertEquals(Optional.of("/Users/alice/wiki"), settings.wikiDir(ALICE));
        assertEquals(Optional.empty(), settings.skillsDir(BOB));
        assertEquals(Optional.empty(), settings.wikiDir(BOB));

        settings.setSkillsDir(ALICE, "   ");
        assertEquals(Optional.empty(), settings.skillsDir(ALICE));
        assertEquals(Optional.of("/Users/alice/wiki"), settings.wikiDir(ALICE), "clearing one leaves the other");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/srv/skills", "~", "~/skills", "~\\skills", "C:\\Users\\yi\\skills", "d:/skills", "\\\\nas\\share\\skills"})
    void absolutePathsInAnyOsSpellingAreAccepted(String dir) {
        settings.setSkillsDir(ALICE, dir);
        assertEquals(Optional.of(dir), settings.skillsDir(ALICE));
    }

    @ParameterizedTest
    @CsvSource({"skills,dir-not-absolute", "./skills,dir-not-absolute", "~yi/skills,dir-not-absolute", "C:skills,dir-not-absolute"})
    void relativePathsAreRefusedAndNothingIsStored(String dir, String code) {
        settings.setWikiDir(ALICE, "/keep");
        assertEquals(code, assertThrows(IllegalArgumentException.class, () -> settings.setWikiDir(ALICE, dir)).getMessage());
        assertEquals(Optional.of("/keep"), settings.wikiDir(ALICE));
    }

    @Test
    void controlCharactersAndOverlongPathsAreRefused() {
        assertEquals("dir-invalid", assertThrows(IllegalArgumentException.class, () -> settings.setSkillsDir(ALICE, "/a\nb")).getMessage());
        assertEquals("dir-too-long",
                assertThrows(IllegalArgumentException.class, () -> settings.setSkillsDir(ALICE, "/" + "a".repeat(1000))).getMessage());
        settings.setSkillsDir(ALICE, "/" + "a".repeat(999));
        assertEquals(1000, settings.skillsDir(ALICE).orElseThrow().length());
    }

    @Test
    void skillsAreTurnedOffAndBackOnPerUserWithoutTouchingTheDirectory() {
        settings.setSkillsDir(ALICE, "/skills");
        settings.setOff(ALICE, "pdf-tools", true);
        settings.setOff(ALICE, "deploy", true);
        settings.setOff(ALICE, "deploy", true);
        assertEquals(Set.of("deploy", "pdf-tools"), settings.off(ALICE));
        assertEquals(Set.of(), settings.off(BOB));

        settings.setOff(ALICE, "deploy", false);
        settings.setOff(ALICE, "never-off", false);
        assertEquals(Set.of("pdf-tools"), settings.off(ALICE));
        settings.setOff(ALICE, "pdf-tools", false);
        assertEquals(Set.of(), settings.off(ALICE));
        assertEquals(Optional.of("/skills"), settings.skillsDir(ALICE));

        settings.setOff(ALICE, "pdf-tools", true);
        settings.setSkillsDir(ALICE, "");
        assertEquals(Set.of("pdf-tools"), settings.off(ALICE), "clearing the directory keeps the choices for when it comes back");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Pdf", "a b", "../x", "a_b"})
    void aNameThatCannotBeASkillIsRefused(String name) {
        assertEquals("name-invalid", assertThrows(IllegalArgumentException.class, () -> settings.setOff(ALICE, name, true)).getMessage());
    }
}
