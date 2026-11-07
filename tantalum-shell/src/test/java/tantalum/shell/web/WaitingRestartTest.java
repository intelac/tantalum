package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.ResultWriter;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.store.SessionStore;
import tantalum.value.event.Answered;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.RunInterrupted;
import tantalum.value.event.TimerFired;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.OwnerRef;

/**
 * waiting-state 需求 3：用一份“进程停在等用户回答”的数据目录启动真实服务。等人的运行不按“因重启中断”收尾，问题还在，
 * 回答后接着跑；带期限的到期后按“没有回答”继续。
 */
class WaitingRestartTest {

    private static final Principal ALICE = new Principal("alice");
    private static final SessionId S1 = new SessionId("s1");
    private static final RunId RUN = RunId.of(S1, 1);
    /** 过去的时刻：带期限的问题在服务启动前就过期了。 */
    private static final Instant AT = Instant.parse("2026-11-02T00:00:00Z");

    @TempDir
    Path temp;

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final ModelClient model = (prompt, chunks) -> {
        prompts.add(prompt);
        return new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);
    };

    private Path data() {
        return temp.resolve("data");
    }

    private TestServer server() throws Exception {
        Files.createDirectories(temp.resolve("workspace"));
        return new TestServer(data(), temp.resolve("workspace"), model);
    }

    private static EventEnvelope event(long seq, RunId run, Event event) {
        return new EventEnvelope(seq, AT, ALICE, S1, run, event);
    }

    /** 模型调了 ask_user，运行停在等人；{@code timeoutSeconds} 为 0 表示一直等。 */
    private void diedWhileWaiting(int timeoutSeconds) throws Exception {
        Files.createDirectories(data());
        Map<String, Json> arguments = new java.util.LinkedHashMap<>();
        arguments.put("question", new Json.Str("Delete the backups?"));
        arguments.put("choices", new Json.Arr(List.of(new Json.Str("delete"), new Json.Str("keep"))));
        if (timeoutSeconds > 0) {
            arguments.put("timeout_seconds", new Json.Num(BigDecimal.valueOf(timeoutSeconds)));
        }
        ResultWriter.write(new SessionPaths(data()), List.of(
                event(1, null, new UserPrompt(new SubmissionId("sub"), new RequestId("req"), new OwnerRef.OfSession(S1), "clean up")),
                event(2, RUN, new ContextAssembled(new CommandId("s1_1-0"), "s", List.of(), null, List.of())),
                event(3, RUN, new ModelReplied(new CommandId("s1_2-0"),
                        List.of(new ToolCallBlock("a1", "ask_user", "{}", new Json.Obj(arguments))), StopReason.TOOL_USE, "m", null, null, null, null))));
    }

    private List<Event> journal() {
        return new SessionStore(data(), EventCodec.withBuiltins()).readAll().stream().map(EventEnvelope::event).toList();
    }

    private void awaitPrompts(int count) throws Exception {
        for (int i = 0; i < 500 && prompts.size() < count; i++) {
            Thread.sleep(10);
        }
        assertEquals(count, prompts.size(), prompts.toString());
    }

    // 需求 3.1、6.1：重启不收尾，问题从 journal 还原；侧栏标记；回答后继续
    @Test
    void aWaitingRunSurvivesTheRestartAndTakesTheAnswer() throws Exception {
        diedWhileWaiting(0);
        try (TestServer server = server()) {
            String sessions = server.get("alice", "/api/sessions").body();
            assertTrue(sessions.contains("\"runStatus\":\"AwaitingAnswer\""), sessions);
            assertTrue(sessions.contains("\"awaitingAnswer\":true"), sessions);
            String questions = server.get("alice", "/api/sessions/s1/questions").body();
            assertTrue(questions.contains("\"id\":\"ask-a1\""), questions);
            assertTrue(questions.contains("Delete the backups?"), questions);
            Thread.sleep(200);
            assertEquals(List.of(), prompts, "nothing is re-run at startup");
            assertFalse(journal().stream().anyMatch(RunInterrupted.class::isInstance), "not interrupted");

            assertEquals(200, server.post("alice", "/api/sessions/s1/questions/ask-a1", "{\"choice\":\"keep\"}").statusCode());
            awaitPrompts(1);
            String seen = prompts.getFirst().messages().toString();
            assertTrue(seen.contains("{\\\"choice\\\":\\\"keep\\\"}") || seen.contains("{\"choice\":\"keep\"}"), seen);
            String view = server.get("alice", "/api/runs/" + RUN.value()).body();
            for (int i = 0; i < 300 && !view.contains("\"endReason\""); i++) {
                Thread.sleep(10);
                view = server.get("alice", "/api/runs/" + RUN.value()).body();
            }
            assertTrue(view.contains("\"endReason\":\"COMPLETED\""), view);
        }
        assertEquals(1, journal().stream().filter(Answered.class::isInstance).count());
    }

    // 需求 3.4：期限跨重启；记下的到期时刻已过 → 启动后立刻按“没有回答”结算
    @Test
    void anExpiredDeadlineFiresAfterTheRestart() throws Exception {
        diedWhileWaiting(60);
        try (TestServer server = server()) {
            awaitPrompts(1);
            String seen = prompts.getFirst().messages().toString();
            assertTrue(seen.contains("timeout") && seen.contains("true"), seen);
            String view = server.get("alice", "/api/runs/" + RUN.value()).body();
            for (int i = 0; i < 300 && !view.contains("\"endReason\""); i++) {
                Thread.sleep(10);
                view = server.get("alice", "/api/runs/" + RUN.value()).body();
            }
            assertTrue(view.contains("\"endReason\":\"COMPLETED\""), view);
            assertTrue(server.get("alice", "/api/sessions/s1/questions").body().contains("\"questions\":[]"));
        }
        List<Event> events = journal();
        assertEquals(1, events.stream().filter(TimerFired.class::isInstance).count(), events.toString());
        assertFalse(events.stream().anyMatch(RunInterrupted.class::isInstance));
    }
}
