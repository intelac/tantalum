package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.cli.CliText;
import tantalum.shell.local.TestComputer;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.skill.SkillSettings;
import tantalum.shell.web.TestServer;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Prompt;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.text.Language;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 技能和知识库的整条链路（skills-wiki 任务 14；需求 2.5、3.1、3.6、4.2–4.4、6.2、7.1–7.4）：真服务器，测试电脑上跑的是连接程序
 * 用的同一份 {@code Skills}、{@code WikiPages}。清单进 system；清单变了下一次提问追加给模型、system 不换；模型调 {@code skill}
 * 拿到全文或三句错误之一；目录越界、连接程序太旧、什么都没填时和以前一样；技能目录删掉以后记录照样重放得出来。
 */
class SkillChainTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Principal ALICE = new Principal("alice");

    @TempDir
    Path temp;

    private final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());
    private TestServer server;
    private Path mac;

    /** 提问是 {@code use <名字>} 时调 skill，拿到结果后原样回答；其余直接回答。 */
    private final ModelClient model = (prompt, chunks) -> {
        prompts.add(prompt);
        ContentBlock last = prompt.messages().getLast().content().getLast();
        if (last instanceof ToolResultBlock result) {
            return new ModelOutcome.Replied(List.of(new TextBlock((result.error() ? "ERROR: " : "OK: ") + result.text())),
                    StopReason.END_TURN, "m", null);
        }
        // 清单变了时内核在提问后面追加一条“上下文变化”的消息，所以从后往前找到上一次回答为止
        String asked = null;
        for (int i = prompt.messages().size() - 1; i >= 0 && prompt.messages().get(i).role() == Role.USER && asked == null; i--) {
            asked = prompt.messages().get(i).content().stream()
                    .filter(block -> block instanceof TextBlock text && text.text().startsWith("use "))
                    .map(block -> ((TextBlock) block).text()).findFirst().orElse(null);
        }
        if (asked != null) {
            String name = asked.substring(4);
            return new ModelOutcome.Replied(List.of(new ToolCallBlock("c-" + prompts.size(), "skill", "{\"name\":\"" + name + "\"}",
                    new Json.Obj(Map.of("name", new Json.Str(name))))), StopReason.TOOL_USE, "m", null);
        }
        return new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);
    };

    @BeforeEach
    void start() throws Exception {
        mac = Files.createDirectories(temp.resolve("mac")).toRealPath();
        skill(mac.resolve("skills"), "pdf-tools", "description: Convert PDF files to text.", "Use pdftotext, never OCR.");
        skill(mac.resolve("skills"), "by-hand", "description: Only when asked.\ndisable-model-invocation: true", "Manual steps.");
        Files.createDirectories(mac.resolve("wiki/ops"));
        Files.writeString(mac.resolve("wiki/ops/rollback.md"), "# Rolling back\n\nUse the blue button.");
        Files.writeString(mac.resolve("wiki/index.md"), "Start with ops/rollback.md.");
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static void skill(Path dir, String name, String fields, String body) throws Exception {
        Path folder = Files.createDirectories(dir.resolve(name));
        Files.writeString(folder.resolve("SKILL.md"), "---\nname: " + name + "\n" + fields + "\n---\n" + body + "\n");
    }

    private SkillSettings settings() {
        return server.context.getBean(Engine.class).skillSettings();
    }

    private void fillBoth() {
        settings().setSkillsDir(ALICE, mac.resolve("skills").toString());
        settings().setWikiDir(ALICE, mac.resolve("wiki").toString());
    }

    private void ask(String text, int run) throws Exception {
        String body = "{\"requestId\":\"r" + run + "\",\"mode\":\"prompt\",\"text\":\"" + text + "\"}";
        assertEquals(200, server.post("alice", "/api/sessions/s1/submissions", body).statusCode());
        for (int i = 0; i < 1000; i++) {
            JsonNode runs = JSON.readTree(server.get("alice", "/api/sessions/s1/runs").body()).get("runs");
            if (runs != null && runs.size() == run && runs.get(run - 1).has("endReason")) {
                assertEquals("COMPLETED", runs.get(run - 1).get("endReason").asString(), runs.toString());
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("run " + run + " did not end");
    }

    private static boolean offered(Prompt prompt, String tool) {
        return prompt.tools().stream().filter(spec -> spec.name().equals(tool)).noneMatch(ToolSpec::deferred);
    }

    private String lastMessages() {
        return prompts.getLast().messages().toString();
    }

    /** 模型最后一次收到的工具结果。 */
    private ToolResultBlock result() {
        return (ToolResultBlock) prompts.getLast().messages().getLast().content().getLast();
    }

    private void assertError(String expected) {
        assertTrue(result().error(), result().text());
        assertEquals(expected, result().text());
    }

    @Test
    void theListingsAreInTheSystemPromptAndAChangeIsAppendedOnTheNextRun() throws Exception {
        fillBoth();
        try (TestComputer ignored = server.connectComputer("alice", mac, false)) {
            ask("hello", 1);
            Prompt first = prompts.getLast();
            String system = first.system();
            assertTrue(system.contains("<skills>\n## Skills\n\nA skill is a reusable set of task-specific instructions."), system);
            assertTrue(system.contains("<available_skills>\n- `pdf-tools`: Convert PDF files to text.\n</available_skills>"),
                    "only the skill the model may call is listed: " + system);
            assertTrue(system.contains("<wiki>\n## Wiki\n\nThe user keeps a wiki in " + mac.resolve("wiki") + "."), system);
            assertTrue(system.contains("<wiki_index file=\"index.md\">\nStart with ops/rollback.md.\n</wiki_index>\n\n"
                    + "<wiki_pages>\n- ops/rollback.md — Rolling back\n</wiki_pages>\n</wiki>"), system);
            assertTrue(system.indexOf("<local-workspace>") < system.indexOf("<skills>")
                    && system.indexOf("<skills>") < system.indexOf("<wiki>"), system);
            assertFalse(system.contains("Use pdftotext"), "summaries only: the body is not in the prompt");
            assertTrue(offered(first, "skill"));

            skill(mac.resolve("skills"), "deploy", "description: Ship the service.", "Run make deploy.");
            ask("hello again", 2);
            Prompt second = prompts.getLast();
            assertEquals(system, second.system(), "the system prompt is not replaced within a session");
            assertTrue(lastMessages().contains("- `deploy`: Ship the service."), "the new listing is appended as an update");

            settings().setOff(ALICE, "deploy", true);
            Files.writeString(mac.resolve("wiki/new-page.md"), "# Fresh\n");
            ask("and again", 3);
            String third = lastMessages();
            assertTrue(third.contains("- new-page.md — Fresh"), third);
            assertEquals(system, prompts.getLast().system());
        }
    }

    @Test
    void theModelLoadsASkillOrGetsOneOfTheThreeErrors() throws Exception {
        fillBoth();
        try (TestComputer ignored = server.connectComputer("alice", mac, false)) {
            ask("use pdf-tools", 1);
            assertFalse(result().error(), result().text());
            assertEquals("<skill_content name=\"pdf-tools\">\n<skill_resources>\nBase directory for this skill: "
                    + mac.resolve("skills/pdf-tools") + "\nResolve relative paths mentioned by this skill against the base directory before"
                    + " using them. Load referenced resources only as needed.\n</skill_resources>\n\n<skill_instructions>\n"
                    + "Use pdftotext, never OCR.\n</skill_instructions>\n</skill_content>", result().text());

            ask("use nope", 2);
            assertError("Error: skill \"nope\" is unknown or no longer available");
            ask("use by-hand", 3);
            assertError("Error: skill \"by-hand\" is not available for model invocation");
            ask("use Bad_Name", 4);
            assertError("Error: invalid skill name \"Bad_Name\"");

            settings().setOff(ALICE, "pdf-tools", true);
            ask("use pdf-tools", 5);
            assertError("Error: skill \"pdf-tools\" is unknown or no longer available");
        }
    }

    /** 目录不在电脑允许的范围里：不读，system 里没有这两段，运行照常（需求 2.2、6.1）。 */
    @Test
    void directoriesOutsideTheAllowedOnesAreNotRead() throws Exception {
        Path elsewhere = Files.createDirectories(temp.resolve("elsewhere")).toRealPath();
        skill(elsewhere.resolve("skills"), "secret", "description: Should never be seen.", "x");
        Files.createDirectories(elsewhere.resolve("wiki"));
        Files.writeString(elsewhere.resolve("wiki/secret.md"), "# Secret page");
        settings().setSkillsDir(ALICE, elsewhere.resolve("skills").toString());
        settings().setWikiDir(ALICE, elsewhere.resolve("wiki").toString());
        try (TestComputer ignored = server.connectComputer("alice", mac, false)) {
            ask("use secret", 1);
            String system = prompts.getLast().system();
            assertFalse(system.contains("<skills>"), system);
            assertFalse(system.contains("<wiki>"), system);
            assertFalse(system.contains("ecret"), system);
            assertError("Error: skill \"secret\" is unknown or no longer available");
        }
    }

    /** 协议 3 的连接程序：不发新字段，没有这两段，skill 工具不给模型；别的照旧（需求 2.5）。 */
    @Test
    void anOlderConnectorWorksAsBefore() throws Exception {
        fillBoth();
        Files.writeString(mac.resolve("AGENTS.md"), "house rules");
        try (TestComputer computer = server.connectComputer("alice", mac, false, 3)) {
            ask("hello", 1);
            Prompt prompt = prompts.getLast();
            assertFalse(prompt.system().contains("<skills>"), prompt.system());
            assertFalse(prompt.system().contains("<wiki>"), prompt.system());
            assertTrue(prompt.system().contains("house rules"));
            assertFalse(offered(prompt, "skill"));
            assertTrue(offered(prompt, "read_file"));
            assertNull(computer.contexts().getFirst().skills());
            assertNull(computer.contexts().getFirst().wiki());
        }
    }

    /** 什么都没填的用户：system 里不多出空段，skill 工具不给模型（需求 7.4、8.2、8.8）。 */
    @Test
    void withNothingFilledInNothingChanges() throws Exception {
        try (TestComputer computer = server.connectComputer("alice", mac, false)) {
            ask("hello", 1);
            Prompt prompt = prompts.getLast();
            assertFalse(prompt.system().contains("<skills>"), prompt.system());
            assertFalse(prompt.system().contains("<wiki>"), prompt.system());
            assertTrue(prompt.system().endsWith("</local-workspace>"), prompt.system());
            assertFalse(offered(prompt, "skill"));
            assertNull(computer.contexts().getFirst().skills());
        }
    }

    /** 清单和加载到的全文都在记录里：技能目录后来删了，重放的结果还是和记录的一致（需求 7.1、7.2）。 */
    @Test
    void aRecordedSessionReplaysAfterTheSkillsAreGone() throws Exception {
        fillBoth();
        try (TestComputer ignored = server.connectComputer("alice", mac, false)) {
            ask("use pdf-tools", 1);
            skill(mac.resolve("skills"), "deploy", "description: Ship the service.", "Run make deploy.");
            ask("hello", 2);
        }
        server.close();
        try (Stream<Path> files = Files.walk(mac)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = RecordCheckCommand.run(new String[] {"--tantalum.data-dir=" + temp.resolve("data"), "--tantalum.workspace=" + temp.resolve("ws")},
                new PrintStream(out, true, StandardCharsets.UTF_8), CliText.of(Language.EN));
        assertEquals(0, code, out.toString(StandardCharsets.UTF_8));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("all 1 sessions"), out.toString(StandardCharsets.UTF_8));
    }
}
