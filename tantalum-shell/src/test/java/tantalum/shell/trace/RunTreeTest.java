package tantalum.shell.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.trace.RunTree.Kind;
import tantalum.shell.trace.RunTree.Node;
import tantalum.shell.trace.RunTree.State;
import tantalum.shell.trace.TraceRecord.Attempt;
import tantalum.shell.trace.TraceRecord.Head;
import tantalum.shell.trace.TraceRecord.HookDecision;
import tantalum.shell.trace.TraceRecord.ModelRequest;
import tantalum.shell.trace.TraceRecord.PluginWork;
import tantalum.shell.trace.TraceRecord.Question;
import tantalum.shell.trace.TraceRecord.RunHead;
import tantalum.shell.trace.TraceRecord.ToolCall;
import tantalum.shell.trace.TraceRecord.UserInput;
import tantalum.value.id.RunId;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.EndReason;

/** 每种记录在树里的位置、状态和时长（run-tree 需求 1、2）。统计口径的对照在 WireFormatTest。 */
class RunTreeTest {

    private static final RunId RUN = new RunId("s_5");
    private static final Instant T0 = Instant.parse("2026-11-15T00:00:00Z");

    private static Instant at(long millis) {
        return T0.plusMillis(millis);
    }

    private static Head head(String id, String pos, Integer step, long from, Long to) {
        return new Head(id, pos, RUN, 1, step, null, at(from), to == null ? null : at(to));
    }

    private static RunHead runHead(Long end, EndReason reason) {
        return new RunHead(head("r", "5.0", null, 0, end), RunHead.Trigger.PROMPT, "RUNNING", reason, null);
    }

    private static ModelRequest request(String id, String pos, int step, String provider, String model, TokenUsage usage, long from, long to) {
        Attempt attempt = new Attempt(id + "-call", at(from), at(from), at(from + 100), at(to), Attempt.Outcome.REPLIED, null, null, null);
        return new ModelRequest(head(id, pos, step, from, to), 1, ModelRequest.Purpose.NORMAL, ModelRequest.Status.DONE, List.of(attempt), 3,
                provider, model, null, 0, usage, usage, null, null, null, null, null, List.of());
    }

    private static ToolCall tool(String id, String pos, int step, ToolCall.Status status, Long startedAt, Long end, String code, String reason) {
        return new ToolCall(head(id, pos, step, 1_000, end), id, "bash", null, "{}", null, status, null, code, reason, "g", at(1_000),
                startedAt == null ? null : at(startedAt), "req");
    }

    private static Node child(Node node, Kind kind) {
        return node.children().stream().filter(found -> found.kind() == kind).findFirst().orElseThrow();
    }

    @Test
    void anOpenRunWaitingForApprovalIsWaitingAndHasNoDuration() {
        Question question = new Question(head("q", "7.1", 1, 1_000, null), "run bash?", "approval", null, Question.Status.WAITING, null, null, "t");
        RunTree tree = RunTree.of(RUN, List.of(runHead(null, null),
                request("m", "6.0", 1, "local", "qwen", new TokenUsage(10, 5, 0, 0), 100, 900),
                tool("t", "7.0", 1, ToolCall.Status.WAITING, null, null, null, null), question));
        Node root = tree.root();
        assertEquals(State.WAITING, root.state());
        assertNull(root.ms());
        Node step = child(root, Kind.STEP);
        assertEquals(State.WAITING, step.state());
        assertNull(step.ms());
        assertEquals(State.WAITING, child(step, Kind.TOOL).state());
        assertEquals(List.of("local/qwen"), root.tally().models());
    }

    @Test
    void theWaitForApprovalIsNotToolTime() {
        // 1.0 秒发出、等批准到 4.0 秒才开始、4.5 秒结束：工具用时 500 毫秒
        RunTree tree = RunTree.of(RUN, List.of(runHead(5_000L, EndReason.COMPLETED),
                request("m", "6.0", 1, "local", "qwen", new TokenUsage(10, 5, 0, 0), 100, 900),
                tool("t", "7.0", 1, ToolCall.Status.DONE, 4_000L, 4_500L, null, null),
                new Question(head("q", "7.1", 1, 1_000, 4_000L), "run bash?", "approval", null, Question.Status.ANSWERED, "yes", null, "t")));
        Node step = child(tree.root(), Kind.STEP);
        assertEquals(500L, step.tally().toolMs());
        Node tool = child(step, Kind.TOOL);
        assertEquals(at(4_000), tool.start());
        assertEquals(500L, tool.ms());
        assertEquals(500L, tree.root().tally().toolMs());
        assertEquals(5_000L, tree.root().ms());
        assertEquals(State.OK, tree.root().state());
    }

    @Test
    void failedToolsHookErrorsAndFailedWorkAreErrorsWithAShortDetail() {
        String longReason = "x".repeat(500);
        HookDecision hook = new HookDecision(head("h", "6.1", 1, 950, 950L), "quota", null, HookDecision.Result.ERROR, longReason, 0, List.of(),
                null, null, null, null);
        PluginWork work = new PluginWork(head("w", "6.2", 1, 960, 990L), "cmd", "lookup", "cite", null, "h", null, null,
                PluginWork.Status.FAILED, null, null, null, at(960));
        RunTree tree = RunTree.of(RUN, List.of(runHead(3_000L, EndReason.FAILED),
                request("m", "6.0", 1, "local", "qwen", new TokenUsage(10, 5, 0, 0), 100, 900), hook, work,
                tool("t", "7.0", 1, ToolCall.Status.ERROR, 1_000L, 1_200L, "EXIT_1", "command failed")));
        Node root = tree.root();
        assertEquals(State.ERROR, root.state());
        Node step = child(root, Kind.STEP);
        assertEquals(State.ERROR, step.state());
        Node hookNode = child(step, Kind.HOOK);
        assertEquals(State.ERROR, hookNode.state());
        assertEquals(RunTree.DETAIL_CHARS, hookNode.detail().length());
        assertEquals(State.ERROR, child(step, Kind.WORK).state());
        assertEquals("EXIT_1: command failed", child(step, Kind.TOOL).detail());
        assertEquals(1, root.tally().hooks());
        assertEquals(1, root.tally().hookErrors());
        assertEquals(1, root.tally().toolErrors());
        assertEquals(List.of(new Tally.ToolCount("bash", 1, 1)), root.tally().tools());
    }

    @Test
    void groupsFollowTheRequestPositionAndLooseRecordsTheirOwn() {
        UserInput prompt = TraceRecordFixture.userInput(head("u", "5.1", null, 0, 0L), UserInput.Mode.PROMPT, "hi", TraceRecord.Source.USER);
        // 第 10 号事件排在第 9 号之后（按数字，不按字符串）
        RunTree tree = RunTree.of(RUN, List.of(runHead(20_000L, EndReason.COMPLETED),
                request("m2", "10.0", 2, "claude", "claude-x", new TokenUsage(30, 1, 0, 0), 12_000, 13_000), prompt,
                request("m1", "9.0", 1, "claude", "claude-x", new TokenUsage(20, 2, 0, 0), 10_000, 11_000)));
        Node root = tree.root();
        assertEquals(List.of(Kind.INPUT, Kind.STEP, Kind.STEP), root.children().stream().map(Node::kind).toList());
        assertEquals("step 1", root.children().get(1).name());
        assertEquals("r/s1", root.children().get(1).id());
        assertEquals("r", root.children().get(1).parent());
        assertEquals(30L, root.tally().context());
        assertEquals(3L, root.tally().tokens().output());
        assertEquals(List.of("claude/claude-x"), root.tally().models());
        Node request = child(root.children().get(1), Kind.REQUEST);
        assertEquals("m1#1", request.children().getFirst().id());
        assertEquals(at(10_100), request.children().getFirst().firstToken(), "the attempt keeps its first-token time for the bar split");
        assertEquals("m1", request.children().getFirst().record());
    }

    @Test
    void withoutARunHeadTheRootIsTheRunItself() {
        RunTree tree = RunTree.of(RUN, List.of(request("m", "6.0", 1, null, null, null, 100, 900)));
        assertEquals("s_5", tree.root().id());
        assertNull(tree.root().record());
        assertNull(tree.root().ms());
        assertTrue(tree.root().tally().models().isEmpty());
        assertEquals(0L, tree.root().tally().tokens().output());
        assertNull(tree.root().tally().context());
    }
}
