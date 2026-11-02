package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.boot.Engine;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.KernelState;

/** 任务 30、31：重启只载入未结束的会话，其余按需只读；查询结果、会话列表和额度都和重启前一致。 */
class ColdSessionTest {

    private static final Principal ALICE = new Principal("alice");
    private static final long QUOTA = 1000;

    @TempDir
    Path temp;

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final ModelClient model = (prompt, chunks) -> {
        prompts.add(prompt);
        return new ModelOutcome.Replied(List.of(new TextBlock("reply " + prompts.size())), StopReason.END_TURN, "m",
                new TokenUsage(800, 10, 0, 0));
    };

    /** 读当天该用户全部会话的累计：重启后如果用量没载入，它会看到 0。 */
    private static final KernelHook QUOTA_HOOK = new KernelHook() {
        @Override public String name() { return "quota"; }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
            var principal = state.principals().get(context.run().principal());
            if (principal == null) {
                return Allow.PLAIN;
            }
            TokenUsage today = principal.usageOn(LocalDate.ofInstant(context.at(), ZoneOffset.UTC));
            return today.promptTotal() + today.output() >= QUOTA ? new Stop("quota") : Allow.PLAIN;
        }
    };

    private TestServer server() throws Exception {
        Files.createDirectories(temp.resolve("workspace"));
        return new TestServer(temp.resolve("data"), temp.resolve("workspace"), model,
                context -> context.registerBean("quota", KernelHook.class, () -> QUOTA_HOOK));
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 1000 && !condition.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    private static void prompt(Engine engine, SessionId session, String text) throws InterruptedException {
        assertEquals(200, engine.submit(ALICE, session, new RequestId(UUID.randomUUID().toString()), "follow-up", text).status());
        await("run of " + session.value() + " ended", () -> engine.state().sessions().get(session) != null
                && engine.state().sessions().get(session).activeRun() == null);
    }

    private record Bodies(String sessions, String messages, String runs, String run) {}

    private static Bodies read(TestServer server, String run) throws Exception {
        return new Bodies(server.get("alice", "/api/sessions").body(), server.get("alice", "/api/sessions/s1/messages").body(),
                server.get("alice", "/api/sessions/s1/runs").body(), server.get("alice", "/api/runs/" + run).body());
    }

    @Test
    void afterARestartIdleSessionsStayColdAndReadExactlyAsBefore() throws Exception {
        Bodies before;
        String run;
        try (TestServer server = server()) {
            Engine engine = server.context.getBean(Engine.class);
            prompt(engine, new SessionId("s1"), "first question\nsecond line");
            prompt(engine, new SessionId("s2"), "another session");
            run = engine.state().sessions().get(new SessionId("s1")).lastRun().value();
            before = read(server, run);
        }
        try (TestServer server = server()) {
            Engine engine = server.context.getBean(Engine.class);
            assertTrue(engine.state().sessions().isEmpty(), "nothing unfinished, nothing loaded");
            Bodies after = read(server, run);
            assertEquals(before, after);
            assertTrue(after.sessions().contains("first question") && after.sessions().contains("another session"), after.sessions());
            assertTrue(engine.state().sessions().isEmpty(), "reading does not make a session resident");
            assertEquals(404, server.get("bob", "/api/sessions/s1/messages").statusCode());
            assertEquals(404, server.get("bob", "/api/runs/" + run).statusCode());
            assertEquals(404, server.get("bob", "/api/sessions/s1/runs").statusCode());
            assertTrue(engine.state().sessions().isEmpty());
        }
    }

    /** 重启后第一个事件把该用户的用量带进内存：第一次调用还在额度内，第二次被停下，和没重启时一样。 */
    @Test
    void theQuotaStillCountsUsageFromBeforeTheRestart() throws Exception {
        try (TestServer server = server()) {
            prompt(server.context.getBean(Engine.class), new SessionId("s1"), "uses 810 tokens");
        }
        assertEquals(1, prompts.size());
        try (TestServer server = server()) {
            Engine engine = server.context.getBean(Engine.class);
            prompt(engine, new SessionId("s2"), "810 so far, allowed");
            assertEquals(2, prompts.size());
            prompt(engine, new SessionId("s3"), "1620 so far, stopped");
            assertEquals(2, prompts.size(), "the hook must see usage from before the restart");
            String runs = server.get("alice", "/api/sessions/s3/runs").body();
            assertTrue(runs.contains("STOPPED"), runs);
        }
    }

    /** 回收和冷查询同时打开：运行结束就离开内存，查询照样完整，手动压缩的检查也看得到冷会话。 */
    @Test
    void anEvictedSessionIsStillReadableAndCompactable() throws Exception {
        Files.createDirectories(temp.resolve("workspace"));
        try (TestServer server = new TestServer(temp.resolve("data"), temp.resolve("workspace"), model,
                "--tantalum.residency.idle=0s", "--tantalum.residency.max-resident=1000",
                "--tantalum.residency.snapshot-events=1", "--tantalum.residency.sweep-interval=0s")) {
            Engine engine = server.context.getBean(Engine.class);
            SessionId s1 = new SessionId("s1");
            assertEquals(200, engine.submit(ALICE, s1, new RequestId("r1"), "follow-up", "hello").status());
            await("evicted", () -> prompts.size() == 1 && !engine.state().sessions().containsKey(s1));
            String messages = server.get("alice", "/api/sessions/s1/messages").body();
            assertTrue(messages.contains("hello") && messages.contains("reply 1") && messages.contains("COMPLETED"), messages);
            assertEquals(409, engine.compact(ALICE, s1, new RequestId("c1"), null).status(), "found cold; nothing worth compacting");
            assertEquals(404, engine.compact(new Principal("bob"), s1, new RequestId("c2"), null).status());
        }
    }
}
