package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.ResultWriter;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.event.ContextAssembledFixture;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.Assignment;
import tantalum.value.event.Root;
import tantalum.value.event.SessionCreated;
import tantalum.value.state.HelperOf;
import tantalum.value.state.SessionKind;
import tantalum.value.event.UserPromptFixture;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.ReportBlock;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.assignment.AssignmentOutcome;
import tantalum.value.assignment.AssignmentTools;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 进程死在任务的各个时间点（subagent 需求 4.1–4.3）：用一份“死在那一刻”的账启动真服务器，放出任务的会话都恰好得到一个结果，
 * 任务不重新执行，再重启一次什么都不多。
 */
class TaskRestartTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Principal ALICE = new Principal("alice");
    private static final SessionId S1 = new SessionId("s1");
    private static final RunId RUN = RunId.of(S1, 1);
    /** 第 3 个事件里第 0 个工具调用放出的任务，和它的会话。 */
    private static final String TASK = "t3-0";
    private static final SessionId CHILD = new SessionId("s1-" + TASK);
    /** 第 5 个事件建帮手会话，第 6 个是交代：它开的那一轮。 */
    private static final RunId CHILD_RUN = RunId.of(CHILD, 6);
    private static final Instant AT = Instant.parse("2026-11-17T00:00:00Z");

    @TempDir
    Path temp;

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final ModelClient model = (prompt, chunks) -> {
        prompts.add(prompt);
        return new ModelOutcome.Replied(List.of(new TextBlock("noted")), StopReason.END_TURN, "m", null);
    };

    private Path data() {
        return temp.resolve("data");
    }

    private TestServer server() throws Exception {
        Files.createDirectories(temp.resolve("workspace"));
        return new TestServer(data(), temp.resolve("workspace"), model);
    }

    private static ModelReplied said(String command, String text) {
        return new ModelReplied(new CommandId(command), List.of(new TextBlock(text)), StopReason.END_TURN, "m", null, null, null, null);
    }

    /** 主会话放出了一个任务，那一轮说完结束了；{@code child} 是任务自己的会话里已经写下的事件。 */
    private void write(Event... child) throws Exception {
        Files.createDirectories(data());
        List<EventEnvelope> events = new ArrayList<>(List.of(
                new EventEnvelope(0, AT, ALICE, S1, null, new SessionCreated(SessionKind.REGULAR, null, false, null)),
                new EventEnvelope(1, AT, ALICE, S1, null, UserPromptFixture.of(new SubmissionId("sub"), new RequestId("req"), "look into module A")),
                new EventEnvelope(2, AT, ALICE, S1, RUN, ContextAssembledFixture.of(new CommandId("s1_1-0"), "s", List.of(), null, List.of())),
                new EventEnvelope(3, AT, ALICE, S1, RUN, new ModelReplied(new CommandId("s1_2-0"), List.of(new ToolCallBlock("c1", AssignmentTools.TASK, "{}",
                        new Json.Obj(Map.of(AssignmentTools.PROMPT, new Json.Str("Check module A."))))), StopReason.TOOL_USE, "m", null, null, null, null)),
                // 第 3 步发出的命令：0 是 Assign，1 是接着调模型
                new EventEnvelope(4, AT, ALICE, S1, RUN, said("s1_3-1", "started"))));
        long seq = 6;
        if (child.length > 0) {
            // 和交代的处理器一样：先建帮手会话
            events.add(new EventEnvelope(5, AT, ALICE, CHILD, null, new SessionCreated(SessionKind.HELPER, null, false,
                    new HelperOf(S1, TASK, null, null, null))));
        }
        for (Event event : child) {
            events.add(new EventEnvelope(seq, AT, ALICE, CHILD, seq == 6 ? null : CHILD_RUN, event));
            seq++;
        }
        ResultWriter.write(new SessionPaths(data()), events);
    }

    private static Assignment assigned() {
        return new Assignment(TASK, S1, Root.USER, "Check module A.", null);
    }

    private static JsonNode runs(TestServer server, SessionId session) throws Exception {
        return JSON.readTree(server.get("alice", "/api/sessions/" + session.value() + "/runs").body()).get("runs");
    }

    /** 等主会话的第二轮（任务的结果开的）结束，返回它看到的任务结果。 */
    private ReportBlock resultSeen(TestServer server) throws Exception {
        for (int i = 0; i < 1500; i++) {
            JsonNode runs = runs(server, S1);
            if (runs.size() == 2 && runs.get(1).has("endReason")) {
                break;
            }
            Thread.sleep(10);
        }
        assertEquals(2, runs(server, S1).size(), runs(server, S1).toString());
        assertEquals(1, prompts.size(), "the result started exactly one run, and the task was not run again");
        List<ReportBlock> results = prompts.getFirst().messages().stream().flatMap(message -> message.content().stream())
                .filter(ReportBlock.class::isInstance).map(ReportBlock.class::cast).toList();
        assertEquals(1, results.size(), results.toString());
        JsonNode tasks = JSON.readTree(server.get("alice", "/api/sessions/s1/tasks").body()).get("tasks");
        for (JsonNode task : tasks) {
            assertFalse(task.get("open").asBoolean(), tasks.toString());
        }
        return results.getFirst();
    }

    /** 再重启一次：没有任务在外面了，什么都不多发生。 */
    private void nothingMoreAfterAnotherRestart() throws Exception {
        try (TestServer server = server()) {
            Thread.sleep(300);
            assertEquals(2, runs(server, S1).size());
            assertEquals(1, prompts.size());
        }
    }

    @Test
    void diedAfterStartingTheTaskBeforeItArrived() throws Exception {
        write();
        try (TestServer server = server()) {
            assertEquals(new ReportBlock(TASK, AssignmentOutcome.INTERRUPTED, null), resultSeen(server));
            // 任务的会话从来没有过
            assertTrue(runs(server, CHILD) == null || runs(server, CHILD).isEmpty());
        }
        nothingMoreAfterAnotherRestart();
    }

    @Test
    void diedWhileTheTaskWasRunning() throws Exception {
        write(assigned(), ContextAssembledFixture.of(new CommandId("s1-t3-0_6-0"), "s", List.of(), null, List.of()));
        try (TestServer server = server()) {
            ReportBlock result = resultSeen(server);
            assertEquals(TASK, result.assignment());
            assertEquals(AssignmentOutcome.INTERRUPTED, result.outcome());
            assertEquals("CANCELLED", runs(server, CHILD).get(0).get("endReason").asString());
        }
        nothingMoreAfterAnotherRestart();
    }

    @Test
    void diedAfterTheTaskEndedBeforeItsResultWasHandedBack() throws Exception {
        write(assigned(), ContextAssembledFixture.of(new CommandId("s1-t3-0_6-0"), "s", List.of(), null, List.of()),
                said("s1-t3-0_7-0", "Module A is fine."));
        try (TestServer server = server()) {
            assertEquals(new ReportBlock(TASK, AssignmentOutcome.COMPLETED, "Module A is fine."), resultSeen(server));
            assertEquals("COMPLETED", runs(server, CHILD).get(0).get("endReason").asString());
        }
        nothingMoreAfterAnotherRestart();
    }
}
