package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.PrivateFiles;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.ResultWriter;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
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
                event(2, RUN, new ContextAssembled(new CommandId("s1_1-0"), "s", List.of(), null, List.of())),
                event(3, RUN, new ModelReplied(new CommandId("s1_2-0"),
                        List.of(new ToolCallBlock("t1", "read_file", "{}", new Json.Obj(java.util.Map.of()))), StopReason.TOOL_USE, "m", null, null, null, null))));
        long seq = 4;
        for (Event event : more) {
            events.add(event(seq++, RUN, event));
        }
        ResultWriter.write(new SessionPaths(data()), events);
    }

    private List<Event> journal() {
        return new SessionStore(data(), EventCodec.withBuiltins()).readAll().stream().map(EventEnvelope::event).toList();
    }

    private static String runs(TestServer server) throws Exception {
        return server.get("alice", "/api/sessions/s1/runs").body();
    }

    /** 需求 2.1：启动只载入有未结束运行的会话。 */
    @Test
    void onlyTheUnfinishedSessionIsLoadedAtStartup() throws Exception {
        diedDuringTool();
        SessionId idle = new SessionId("s2");
        ResultWriter.write(new SessionPaths(data()), List.of(new EventEnvelope(10, AT, ALICE, idle, null,
                new tantalum.value.event.Cancel(new RequestId("nothing to cancel")))));
        try (TestServer server = server()) {
            var engine = server.context.getBean(tantalum.shell.boot.Engine.class);
            assertEquals(java.util.Set.of(S1), engine.state().sessions().keySet());
            assertTrue(server.get("alice", "/api/sessions").body().contains("\"id\":\"s1\""));
        }
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

    /** 索引补不上就不知道哪些运行要收尾：拒绝启动，并告诉管理员删掉索引重建。 */
    @Test
    void indexThatCannotCatchUpRefusesToStart() throws Exception {
        diedDuringTool();
        Path userDir = new SessionPaths(data()).userDir(ALICE);
        assumeTrue(PrivateFiles.posix(userDir), "读不了、写不进的文件用 POSIX 权限造；Windows 上没有这个办法，读写失败的处理和平台无关，在 macOS 上测");
        Files.setPosixFilePermissions(userDir, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        try {
            Throwable failure = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, this::server);
            StringBuilder messages = new StringBuilder();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                messages.append(cause.getMessage()).append('\n');
            }
            assertTrue(messages.toString().contains("delete index.db"), messages.toString());
        } finally {
            Files.setPosixFilePermissions(userDir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    /** 一个有未结束运行的会话文件坏了：只拒绝它，其他会话照常收尾，服务照常启动。 */
    @Test
    void corruptUnfinishedSessionIsRefusedAndTheOthersStart() throws Exception {
        diedDuringTool();
        SessionId other = new SessionId("s3");
        RunId otherRun = RunId.of(other, 20);
        ResultWriter.write(new SessionPaths(data()), List.of(
                new EventEnvelope(20, AT, ALICE, other, null, new UserPrompt(new SubmissionId("sub3"), new RequestId("req3"), new OwnerRef.OfSession(other), "hello")),
                new EventEnvelope(21, AT, ALICE, other, otherRun, new ContextAssembled(new CommandId("s3_20-0"), "s", List.of(), null, List.of()))));
        // 先让索引记下两个未结束的运行，再把 s1 的中间一行改坏：启动时索引仍说它没结束，载入时才发现坏了。
        Path s1 = new SessionPaths(data()).sessionFile(ALICE, S1);
        try (var index = tantalum.shell.store.SessionIndex.open(data())) {
            SessionPaths paths = new SessionPaths(data());
            var reader = new tantalum.shell.journal.SessionFileReader(EventCodec.withBuiltins());
            tantalum.shell.store.IndexCatchUp.run(index, paths, reader);
            assertEquals(2, index.unfinished().size());
        }
        List<String> broken = Files.readAllLines(s1);
        broken.set(2, "{\"seq\":2,\"at\":\"2026-11-02T00:00:00Z\",\"type\":\"Mystery\",\"version\":1,\"run\":null,\"payload\":{}}");
        Files.write(s1, broken);

        try (TestServer server = server()) {
            String view = server.get("alice", "/api/runs/" + otherRun.value()).body();
            assertTrue(view.contains("interrupted by restart"), view);
            int status = server.post("alice", "/api/sessions/s1/submissions", "{\"requestId\":\"again\",\"mode\":\"prompt\",\"text\":\"hi\"}").statusCode();
            assertEquals(500, status);
        }
    }
}
