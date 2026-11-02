package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.store.SessionFile;
import tantalum.shell.store.SessionPaths;
import tantalum.shell.store.SessionStore;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.RunInterrupted;
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

/** H9：用一份“进程死在工具调用途中”的文件启动真实服务，看收尾、继续提交和第二次重启。 */
class RestartInterruptTest {

    private static final Principal ALICE = new Principal("alice");
    private static final SessionId S1 = new SessionId("s1");
    private static final RunId RUN = RunId.of(S1, 1);
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

    /** 模型要了工具，工具正在执行时进程死了。 */
    private void diedDuringTool(Event... more) throws Exception {
        Files.createDirectories(data());
        List<EventEnvelope> events = new java.util.ArrayList<>(List.of(
                event(1, null, new UserPrompt(new SubmissionId("sub"), new RequestId("req"), new OwnerRef.OfSession(S1), "hi")),
                event(2, RUN, new ContextAssembled(new CommandId("s1_1-0"), "s", List.of())),
                event(3, RUN, new ModelReplied(new CommandId("s1_2-0"),
                        List.of(new ToolCallBlock("t1", "read_file", "{}", new Json.Obj(java.util.Map.of()))), StopReason.TOOL_USE, "m", null))));
        long seq = 4;
        for (Event event : more) {
            events.add(event(seq++, RUN, event));
        }
        try (SessionFile file = SessionFile.open(new SessionPaths(data()), ALICE, S1, EventCodec.withBuiltins())) {
            file.append(events);
        }
    }

    private List<Event> journal() {
        return new SessionStore(data(), EventCodec.withBuiltins()).readAll().stream().map(EventEnvelope::event).toList();
    }

    private static String runs(TestServer server) throws Exception {
        return server.get("alice", "/api/sessions/s1/runs").body();
    }

    @Test
    void closedBeforeTheFirstRequestWithoutRerunningAnything() throws Exception {
        diedDuringTool();
        try (TestServer server = server()) {
            String view = server.get("alice", "/api/runs/" + RUN.value()).body();
            assertTrue(view.contains("\"endReason\":\"CANCELLED\""), view);
            assertTrue(view.contains("interrupted by restart"), view);
            String sessions = server.get("alice", "/api/sessions").body();
            assertTrue(sessions.contains("\"runStatus\":\"idle\""), sessions);
            String messages = server.get("alice", "/api/sessions/s1/messages").body();
            assertFalse(messages.contains("\"status\":\"RUNNING\""), messages);
            Thread.sleep(200);
            assertEquals(List.of(), prompts);
        }
        assertEquals(1, journal().stream().filter(RunInterrupted.class::isInstance).count());
    }

    @Test
    void userContinuesInTheSameSessionAndTheModelSeesThePairedResult() throws Exception {
        diedDuringTool();
        try (TestServer server = server()) {
            assertEquals(200, server.post("alice", "/api/sessions/s1/submissions",
                    "{\"requestId\":\"r2\",\"mode\":\"prompt\",\"text\":\"again\"}").statusCode());
            for (int i = 0; i < 500 && prompts.isEmpty(); i++) {
                Thread.sleep(10);
            }
            assertEquals(1, prompts.size());
            String seen = prompts.getFirst().messages().toString();
            assertTrue(seen.contains("TOOL_OUTCOME_UNKNOWN"), seen);
        }
    }

    @Test
    void queuedFollowUpStillRuns() throws Exception {
        diedDuringTool(new FollowUpSubmitted(new SubmissionId("sub2"), new RequestId("req2"), "next please"));
        try (TestServer server = server()) {
            for (int i = 0; i < 500 && !runs(server).contains("COMPLETED"); i++) {
                Thread.sleep(10);
            }
            assertTrue(runs(server).contains("COMPLETED"), runs(server));
            assertTrue(prompts.getFirst().messages().toString().contains("next please"));
        }
    }

    @Test
    void secondRestartAddsNothing() throws Exception {
        diedDuringTool();
        try (TestServer server = server()) {
            assertTrue(runs(server).contains("CANCELLED"));
        }
        try (TestServer server = server()) {
            assertTrue(runs(server).contains("CANCELLED"));
        }
        assertEquals(1, journal().stream().filter(RunInterrupted.class::isInstance).count());
    }
}
