package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.boot.Engine;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.event.TextWithdrawn.By;
import tantalum.value.event.TextWithdrawn.Swap;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;

/**
 * 撤下文字走完整条管线（memory-forget-by-choice R2b）：模型以后收到的请求、对话页、会话标题、内容搜索里都不再有它；
 * 重启以后照样（从账里恢复，不再调内核）；不在内存里的会话也能撤；别人的会话是 404。账里的历史一个字不改。
 */
class WithdrawApiTest {

    private static final Principal ALICE = new Principal("alice");
    private static final String PLATE = "my plate is 7Q2KD";
    private static final List<Swap> SWAPS = List.of(new Swap(PLATE, "[withdrawn]"));

    @TempDir
    Path temp;

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final ModelClient model = (prompt, chunks) -> {
        prompts.add(prompt);
        return new ModelOutcome.Replied(List.of(new TextBlock("noted: " + PLATE)), StopReason.END_TURN, "m", new TokenUsage(80, 10, 0, 0));
    };

    private TestServer server() throws Exception {
        Files.createDirectories(temp.resolve("workspace"));
        return new TestServer(temp.resolve("data"), temp.resolve("workspace"), model);
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 1000 && !condition.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    private static void prompt(Engine engine, SessionId session, String text) throws InterruptedException {
        var before = engine.state().sessions().get(session);
        var previous = before == null ? null : before.lastRun();
        assertEquals(200, engine.intake().submit(ALICE, session, new RequestId(UUID.randomUUID().toString()), "follow-up", text).status());
        await("run of " + session.value() + " ended", () -> {
            var current = engine.state().sessions().get(session);
            return current != null && current.lastRun() != null && !current.lastRun().equals(previous) && current.activeRun() == null;
        });
    }

    private static String body(TestServer server, String path) {
        try {
            return server.get("alice", path).body();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** 插件能找到的原话（history_search 和“撤下”找候选用的就是它）。 */
    private static boolean found(TestServer server, String words) {
        return !server.context.getBean(tantalum.shell.plugin.PluginServices.class).conversationLines(ALICE, words, true, 5).isEmpty();
    }

    @Test
    void withdrawnTextIsGoneFromTheModelThePageTheTitleAndSearchAndStaysGoneAfterARestart() throws Exception {
        SessionId first = new SessionId("s1");
        SessionId second = new SessionId("s2");
        try (TestServer server = server()) {
            Engine engine = server.context.getBean(Engine.class);
            prompt(engine, first, PLATE + ", please note it");
            var earlier = engine.state().sessions().get(first).lastRun();
            prompt(engine, second, "in another conversation: " + PLATE);
            assertTrue(body(server, "/api/sessions/s1/messages").contains("7Q2KD"));
            assertTrue(body(server, "/api/sessions").contains("7Q2KD"), "control: it is in the title");
            await("indexed", () -> found(server, "7Q2KD"));

            assertEquals(404, engine.intake().withdraw(new Principal("bob"), first, SWAPS, By.user()));
            assertEquals(200, engine.intake().withdraw(ALICE, first, SWAPS, By.user()));
            await("the page no longer shows it", () -> !body(server, "/api/sessions/s1/messages").contains("7Q2KD"));
            assertTrue(body(server, "/api/sessions/s1/messages").contains("[withdrawn], please note it"));
            await("the title follows", () -> body(server, "/api/sessions").contains("[withdrawn], please note it"));
            // 历史没有改：账里的原话还在
            assertTrue(engine.state().sessions().get(first).messages().toString().contains("7Q2KD"));
            assertEquals(1, engine.state().sessions().get(first).withdrawn().size());

            prompts.clear();
            prompt(engine, first, "what did I tell you?");
            assertFalse(prompts.getLast().messages().toString().contains("7Q2KD"), "the model no longer sees it: " + prompts.getLast().messages());
            assertTrue(prompts.getLast().messages().toString().contains("[withdrawn], please note it"));
            // 之后助手又说了一遍（这里的假模型每次都复述）：那是新的话，不归这次撤下管
            assertTrue(body(server, "/api/sessions/s1/messages").contains("7Q2KD"));
            // 插件读一轮对话（自动记笔记用的就是它）：撤下之前那一轮读不到原话，之后那一轮新说的照常读得到
            var platform = server.context.getBean(tantalum.shell.plugin.PluginServices.class);
            assertFalse(platform.runView(ALICE, first, earlier).toString().contains("7Q2KD"));
            assertTrue(platform.runView(ALICE, first, engine.state().sessions().get(first).lastRun()).toString().contains("7Q2KD"));
        }
        try (TestServer server = server()) {
            Engine engine = server.context.getBean(Engine.class);
            String page = body(server, "/api/sessions/s1/messages");
            assertTrue(page.contains("[withdrawn], please note it") && !page.contains(PLATE + ", please note it"), page);

            // 不在内存里的会话也能撤：平台照别的事件一样把它载入
            assertEquals(200, engine.intake().withdraw(ALICE, second, SWAPS, By.user()));
            await("the cold session's page no longer shows it", () -> !body(server, "/api/sessions/s2/messages").contains("7Q2KD"));
            assertEquals(200, engine.intake().withdraw(ALICE, first, SWAPS, By.user()));
            await("nothing of it is searchable", () -> !found(server, "7Q2KD"));

            prompts.clear();
            prompt(engine, first, "and now?");
            assertFalse(prompts.getLast().messages().toString().contains("7Q2KD"), "after a restart the model still does not see it");
        }
        try (TestServer server = server()) {
            assertFalse(body(server, "/api/sessions").contains("7Q2KD"), "titles after a second restart");
        }
    }

    /** 插件接口的写请求先看长度：超过上限的不读体（review 2026-11-17），平常大小的照常。 */
    @Test
    void anOversizedPluginRequestIsRefusedBeforeItIsRead() throws Exception {
        try (TestServer server = server()) {
            String big = "{\"find\":\"" + "a".repeat(tantalum.shell.auth.AuthBodyLimitFilter.MAX_PLUGIN_BODY) + "\"}";
            assertEquals(413, server.post("alice", "/api/plugin-api/memory/forget", big).statusCode());
            assertEquals(200, server.post("alice", "/api/plugin-api/memory/forget", "{\"find\":\"my plate\"}").statusCode());
        }
    }
}
