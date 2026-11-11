package tantalum.local.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;
import tantalum.value.json.Json;

/** skills-wiki 需求 4：模型按名字加载技能；三句错误逐字来自 DSH-tool“工具错误”。 */
class SkillToolTest {

    @TempDir
    Path temp;

    private Path skills;
    private AllowedDirs dirs;
    private final SkillTool tool = new SkillTool();

    @BeforeEach
    void setUp() throws Exception {
        temp = temp.toRealPath();
        skills = Files.createDirectory(temp.resolve("skills"));
        dirs = new AllowedDirs(List.of(temp));
        Files.writeString(Files.createDirectory(skills.resolve("pdf-tools")).resolve("SKILL.md"), "---\ndescription: PDFs\n---\nUse pdftotext.");
        Files.writeString(Files.createDirectory(skills.resolve("deploy")).resolve("SKILL.md"),
                "---\ndescription: Ship\ndisable-model-invocation: true\n---\nSteps");
        Files.writeString(Files.createDirectory(skills.resolve("broken")).resolve("SKILL.md"), "no front matter");
    }

    private LocalResult run(String skillsDir, Json arguments) {
        return tool.run(new LocalContext("s-1", dirs, new Observations(), LocalContext.NO_SANDBOX, skillsDir), arguments);
    }

    private static Json name(String name) {
        return new Json.Obj(Map.of("name", new Json.Str(name)));
    }

    @Test
    void loadsTheSkillContent() {
        LocalResult result = run(skills.toString(), name("pdf-tools"));
        assertFalse(result.error());
        assertTrue(result.text().startsWith("<skill_content name=\"pdf-tools\">\n"), result.text());
        assertTrue(result.text().contains("<skill_instructions>\nUse pdftotext.\n</skill_instructions>"), result.text());
    }

    @Test
    void theThreeErrorsAreVerbatim() {
        assertEquals(LocalResult.error("Error: invalid skill name \"../x\""), run(skills.toString(), name("../x")));
        assertEquals(LocalResult.error("Error: invalid skill name \"\""), run(skills.toString(), new Json.Obj(Map.of())));
        assertEquals(LocalResult.error("Error: skill \"nope\" is unknown or no longer available"), run(skills.toString(), name("nope")));
        assertEquals(LocalResult.error("Error: skill \"broken\" is unknown or no longer available"), run(skills.toString(), name("broken")));
        assertEquals(LocalResult.error("Error: skill \"deploy\" is not available for model invocation"), run(skills.toString(), name("deploy")));
    }

    /** 调用里没带技能目录（旧服务器、没填目录）：和没有这个技能一样。 */
    @Test
    void withoutASkillsDirectoryEverySkillIsUnknown() {
        assertEquals(LocalResult.error("Error: skill \"pdf-tools\" is unknown or no longer available"), run(null, name("pdf-tools")));
    }

    @Test
    void isReadOnlyAndConcurrencySafe() {
        assertEquals(LocalTool.Kind.SKILL, tool.kind());
        assertTrue(tool.isConcurrencySafe(name("pdf-tools")));
    }
}
