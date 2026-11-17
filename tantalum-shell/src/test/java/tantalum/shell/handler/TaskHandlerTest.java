package tantalum.shell.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.skill.Agents;
import tantalum.value.command.ReturnTask;
import tantalum.value.command.StartTask;
import tantalum.value.command.StopTask;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.TaskAssigned;
import tantalum.value.event.TaskCancelled;
import tantalum.value.event.TaskEnded;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.ModelChoice;
import tantalum.value.task.TaskOutcome;

/**
 * 任务的三条命令（subagent 需求 1–3、6、8）：每条都是往另一个会话放一条事件，对命令本身不产生结果。
 * 放出：任务用放出它的那一轮的模型，agent 的定义另外指定时用它的；点了不认识的 agent，任务不开始，放出它的会话得到一条失败的结果。
 */
class TaskHandlerTest {

    private static final Principal ALICE = new Principal("alice");
    private static final SessionId PARENT = new SessionId("s1");
    private static final SessionId CHILD = new SessionId("s1-t3-0");
    private static final RunRef RUN = new RunRef(ALICE, PARENT, RunId.of(PARENT, 1));
    private static final Instant NOW = Instant.parse("2026-11-18T01:00:00Z");
    private static final ModelChoice SONNET = new ModelChoice("claude", "claude-sonnet-5-5", "medium");
    private static final ModelChoice HAIKU = new ModelChoice("claude", "claude-haiku-5-5", null);

    private final List<EventEnvelope> sent = new ArrayList<>();
    private boolean running = true;
    /** 任务的会话编号认领得到（不是别人的）。 */
    private boolean mine = true;
    private final Agents agents = (user, dir) -> List.of(
            new Agents.Agent("reviewer", "Reviews code.", "You review code.", List.of("read_file", "grep"), null),
            new Agents.Agent("quick", "Fast checks.", "Be quick.", null, HAIKU));

    private TaskHandler handler() {
        return new TaskHandler(Clock.fixed(NOW, ZoneOffset.UTC), sent::add, agents, (user, session) -> running, (user, session) -> mine);
    }

    private static StartTask start(String agent) {
        return new StartTask(new CommandId("c1"), RUN, "t3-0", CHILD, agent, "Check module A.", "/home/alice/app", true, SONNET);
    }

    private EventEnvelope only() {
        assertEquals(1, sent.size(), sent.toString());
        EventEnvelope envelope = sent.getFirst();
        assertEquals(ALICE, envelope.principal());
        assertEquals(NOW, envelope.at());
        assertNull(envelope.run());
        assertEquals(EventEnvelope.UNASSIGNED, envelope.seq());
        return envelope;
    }

    @Test
    void aTaskGoesToItsOwnSessionOnTheModelOfTheRunThatStartedIt() throws Exception {
        assertNull(handler().handle(start(null)));

        EventEnvelope envelope = only();
        assertEquals(CHILD, envelope.session());
        assertEquals(new TaskAssigned("t3-0", PARENT, "Check module A.", null, null, null, SONNET, "/home/alice/app", true), envelope.event());
    }

    @Test
    void aNamedAgentBringsItsInstructionsToolsAndItsOwnModelIfItHasOne() throws Exception {
        handler().handle(start("reviewer"));
        assertEquals(new TaskAssigned("t3-0", PARENT, "Check module A.", "reviewer", "You review code.", List.of("read_file", "grep"), SONNET,
                "/home/alice/app", true), only().event());

        sent.clear();
        handler().handle(start("quick"));
        assertEquals(HAIKU, ((TaskAssigned) only().event()).model());
    }

    @Test
    void anUnknownAgentStartsNothingAndTheStarterIsToldWhichThereAre() throws Exception {
        handler().handle(start("nope"));

        EventEnvelope envelope = only();
        assertEquals(PARENT, envelope.session());
        TaskEnded ended = (TaskEnded) envelope.event();
        assertEquals("t3-0", ended.task());
        assertEquals(TaskOutcome.FAILED, ended.outcome());
        assertTrue(ended.text().contains("\"nope\"") && ended.text().contains("reviewer, quick"), ended.text());
    }

    /** 多用户：任务的会话编号已经是别人的。不往那个会话里送任何东西，放出任务的人得到失败和原因。 */
    @Test
    void whenTheTasksSessionBelongsToSomeoneElseNothingIsSentThere() throws Exception {
        mine = false;
        handler().handle(start(null));

        EventEnvelope envelope = only();
        assertEquals(PARENT, envelope.session());
        assertEquals(ALICE, envelope.principal());
        TaskEnded ended = (TaskEnded) envelope.event();
        assertEquals(TaskOutcome.FAILED, ended.outcome());
        assertTrue(ended.text().startsWith("Not started: the server could not open a session"), ended.text());
    }

    @Test
    void aResultGoesBackToTheSessionThatStartedTheTask() throws Exception {
        RunRef childRun = new RunRef(ALICE, CHILD, RunId.of(CHILD, 5));
        assertNull(handler().handle(new ReturnTask(new CommandId("c2"), childRun, PARENT, "t3-0", TaskOutcome.COMPLETED, "Module A is fine.")));

        EventEnvelope envelope = only();
        assertEquals(PARENT, envelope.session());
        assertEquals(new TaskEnded("t3-0", TaskOutcome.COMPLETED, "Module A is fine."), envelope.event());
    }

    @Test
    void stoppingATaskThatIsRunningTellsItToStopAndOneThatIsNotGivesTheStarterStoppedAtOnce() throws Exception {
        StopTask stop = new StopTask(new CommandId("c3"), RUN, "t3-0", CHILD);
        handler().handle(stop);
        assertEquals(CHILD, only().session());
        assertEquals(new TaskCancelled("t3-0"), only().event());

        // 没送到、或者结束了结果没送回：没有在跑的运行。直接给“已停止”，这样停止一定能把那条记录清掉
        sent.clear();
        running = false;
        handler().handle(stop);
        assertEquals(PARENT, only().session());
        assertEquals(new TaskEnded("t3-0", TaskOutcome.STOPPED, null), only().event());
    }

    @Test
    void aCommandThatFailsHasNoEventOfItsOwn() {
        assertNull(handler().failed(start(null), new IllegalStateException("boom")));
        assertTrue(sent.isEmpty());
    }
}
