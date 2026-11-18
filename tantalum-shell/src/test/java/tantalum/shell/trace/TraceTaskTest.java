package tantalum.shell.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.kernel.Limits;
import tantalum.shell.trace.TraceRecord.RunHead;
import tantalum.shell.trace.TraceRecord.RunHead.Trigger;
import tantalum.shell.trace.TraceRecord.TaskNote;
import tantalum.shell.trace.TraceRecord.ToolCall;
import tantalum.shell.trace.TraceRecord.ToolCall.Status;
import tantalum.shell.trace.TraceRecord.UserInput;
import tantalum.value.event.TaskAssigned;
import tantalum.value.event.TaskEnded;
import tantalum.value.event.TimerFired;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.task.OpenTask;
import tantalum.value.task.TaskLimits;
import tantalum.value.task.TaskOutcome;
import tantalum.value.task.TaskTools;

/**
 * 任务在轨迹里（subagent 需求 10.5）：放任务的调用是一次完成的工具调用，带着任务和它的会话；任务的结果回来是一条“任务”记录，
 * 它开的那一轮写明不是人发起的；为任务开的会话里，交代的内容也是一条“任务”记录。都不是用户输入。
 */
class TraceTaskTest {

    private static ToolCallBlock task(String callId, String prompt) {
        return new ToolCallBlock(callId, TaskTools.TASK, "{}",
                prompt == null ? new Json.Obj(Map.of()) : new Json.Obj(Map.of(TaskTools.PROMPT, new Json.Str(prompt))));
    }

    private static ToolCall tool(TraceFixture fx, String callId) {
        return fx.records(ToolCall.class).stream().filter(tool -> tool.call().equals(callId)).findFirst().orElseThrow();
    }

    /** 放出一个任务并让这一轮说完。 */
    private static OpenTask startOne(TraceFixture fx) {
        RunId run = fx.start("check the three modules");
        fx.feed(run, fx.reply(task("c1", "Check module A.")));
        OpenTask open = fx.state().sessions().get(TraceFixture.SESSION).tasks().open().getFirst();
        fx.feed(run, fx.reply(TraceFixture.text("started")));
        return open;
    }

    @Test
    void startingATaskIsAFinishedToolCallThatNamesTheTaskAndItsSession() {
        TraceFixture fx = new TraceFixture();
        OpenTask open = startOne(fx);

        ToolCall call = tool(fx, "c1");
        assertEquals(Status.DONE, call.status());
        assertEquals(open.id(), call.task());
        assertEquals(open.session().value(), call.taskSession());
        assertTrue(call.result().contains(open.id()), call.result());
        assertTrue(call.finished());
    }

    @Test
    void aRefusedTaskIsAnErrorNotABlockedCallAndNamesNoTask() {
        TraceFixture fx = new TraceFixture();
        RunId run = fx.start("go");
        fx.feed(run, fx.reply(task("c1", null)));

        ToolCall call = tool(fx, "c1");
        assertEquals(Status.ERROR, call.status());
        assertNull(call.task());
        assertNull(call.taskSession());
    }

    @Test
    void aResultThatArrivesWhileIdleStartsARunMarkedAsNotStartedByAPerson() {
        TraceFixture fx = new TraceFixture();
        OpenTask open = startOne(fx);

        fx.feed(null, new TaskEnded(open.id(), TaskOutcome.COMPLETED, "Module A is fine."));

        RunId fresh = RunId.of(TraceFixture.SESSION, fx.lastSeq());
        RunHead head = fx.records(RunHead.class).stream().filter(run -> run.head().run().equals(fresh)).findFirst().orElseThrow();
        assertEquals(Trigger.TASK_RESULT, head.trigger());
        TaskNote note = fx.records(TaskNote.class).getFirst();
        assertEquals(TaskNote.Side.RESULT, note.side());
        assertEquals(fresh, note.head().run());
        assertEquals(open.id(), note.task());
        assertEquals(open.session().value(), note.session());
        assertEquals(TaskOutcome.COMPLETED, note.outcome());
        assertEquals("Module A is fine.", note.text());
        // 不是用户输入：这一轮没有多出用户输入的记录
        assertEquals(1, fx.records(UserInput.class).size());
    }

    @Test
    void aResultThatArrivesDuringARunBelongsToThatRunAndARepeatAddsNothing() {
        TraceFixture fx = new TraceFixture();
        OpenTask open = startOne(fx);
        RunId second = fx.start("anything else?");

        fx.feed(null, new TaskEnded(open.id(), TaskOutcome.FAILED, "could not read it"));
        fx.feed(null, new TaskEnded(open.id(), TaskOutcome.COMPLETED, "late"));

        List<TaskNote> notes = fx.records(TaskNote.class);
        assertEquals(1, notes.size());
        assertEquals(second, notes.getFirst().head().run());
        assertEquals(TaskOutcome.FAILED, notes.getFirst().outcome());
    }

    /** 期限到了也是任务的结果：事件是定时器，不是任务交回来的；之后任务那边交回的“已停止”来晚了，什么都不加。 */
    @Test
    void aTaskThatRunsOutOfTimeLeavesATimedOutRecordAndTheLateStopAddsNothing() {
        TraceFixture fx = new TraceFixture(true, List.of(), new Limits(10, 8, new TaskLimits(4, 10, 10, Duration.ofSeconds(10))));
        OpenTask open = startOne(fx);

        fx.feed(null, new TimerFired(open.timer()));
        RunId fresh = RunId.of(TraceFixture.SESSION, fx.lastSeq());
        fx.feed(null, new TaskEnded(open.id(), TaskOutcome.STOPPED, null));

        List<TaskNote> notes = fx.records(TaskNote.class);
        assertEquals(1, notes.size());
        assertEquals(TaskOutcome.TIMED_OUT, notes.getFirst().outcome());
        assertEquals(fresh, notes.getFirst().head().run());
        assertEquals(open.session().value(), notes.getFirst().session());
        assertNull(notes.getFirst().text());
        RunHead head = fx.records(RunHead.class).stream().filter(run -> run.head().run().equals(fresh)).findFirst().orElseThrow();
        assertEquals(Trigger.TASK_RESULT, head.trigger());
    }

    @Test
    void inTheTasksOwnSessionTheBriefStartsTheRun() {
        TraceFixture fx = new TraceFixture();
        fx.feed(null, new TaskAssigned("t7-0", new SessionId("parent"), "Check module A.", "reviewer", null, null, null, null, false));

        RunHead head = fx.records(RunHead.class).getFirst();
        assertEquals(Trigger.TASK, head.trigger());
        TaskNote note = fx.records(TaskNote.class).getFirst();
        assertEquals(TaskNote.Side.ASSIGNED, note.side());
        assertEquals("parent", note.session());
        assertEquals("reviewer", note.agent());
        assertEquals("Check module A.", note.text());
        assertTrue(fx.records(UserInput.class).isEmpty());
    }

    @Test
    void theRunTreeShowsATaskNodeAndAStoppedTaskAsCancelled() {
        TraceFixture fx = new TraceFixture();
        OpenTask open = startOne(fx);
        fx.feed(null, new TaskEnded(open.id(), TaskOutcome.STOPPED, null));

        RunId fresh = RunId.of(TraceFixture.SESSION, fx.lastSeq());
        List<TraceRecord> records = fx.records().stream().filter(record -> record.head().run().equals(fresh)).toList();
        RunTree tree = RunTree.of(fresh, records);
        assertEquals("TASK_RESULT", tree.root().name());
        RunTree.Node node = flatten(tree.root()).stream().filter(n -> n.kind() == RunTree.Kind.TASK).findFirst().orElseThrow();
        assertEquals(RunTree.State.CANCELLED, node.state());
        assertEquals("RESULT", node.name());
    }

    private static List<RunTree.Node> flatten(RunTree.Node node) {
        List<RunTree.Node> all = new java.util.ArrayList<>(List.of(node));
        node.children().forEach(child -> all.addAll(flatten(child)));
        return all;
    }
}
