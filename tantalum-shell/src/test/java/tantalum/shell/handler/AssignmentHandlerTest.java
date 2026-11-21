package tantalum.shell.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.skill.Agents;
import tantalum.value.assignment.AssignmentOutcome;
import tantalum.value.command.Assign;
import tantalum.value.command.ReturnReport;
import tantalum.value.command.StopAssignment;
import tantalum.value.event.Assignment;
import tantalum.value.event.Input;
import tantalum.value.event.Report;
import tantalum.value.event.Root;
import tantalum.value.event.SessionCreated;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.ModelChoice;
import tantalum.value.state.HelperOf;
import tantalum.value.state.SessionKind;

/**
 * 交代的三条命令（subagent 需求 1–3、6、8；session-model 需求 7）：每条都经入口往另一个会话送一条输入，对命令本身不产生结果。
 * 帮手会话在这里建，出身（交代的会话、agent 的提示词和工具）这时定下；帮手用交代它的那一轮的模型，agent 的定义另外指定时用它的；
 * 点了不认识的 agent、或者编号已经是别人的，不开始，交代的会话得到一条失败的结果；入口说环满了就等一会儿再送。
 */
class AssignmentHandlerTest {

    private static final Principal ALICE = new Principal("alice");
    private static final SessionId ISSUER = new SessionId("s1");
    private static final SessionId HELPER = new SessionId("s1-t3-0");
    private static final RunRef RUN = new RunRef(ALICE, ISSUER, RunId.of(ISSUER, 1));
    private static final ModelChoice SONNET = new ModelChoice("claude", "claude-sonnet-5-5", "medium");
    private static final ModelChoice HAIKU = new ModelChoice("claude", "claude-haiku-5-5", null);

    /** 入口收到的一次送达。{@code create} 是“不存在就这样建”，{@code cancelled} 是叫停的那次交代。 */
    private record Sent(Principal principal, SessionId session, SessionCreated create, Input input, String cancelled) {}

    private final List<Sent> sent = new ArrayList<>();
    /** 入口接下来几次的回答；空了就是 200。 */
    private final Deque<Integer> statuses = new ArrayDeque<>();
    private boolean running = true;
    private final Agents agents = (user, dir) -> List.of(
            new Agents.Agent("reviewer", "Reviews code.", "You review code.", List.of("read_file", "grep"), null),
            new Agents.Agent("quick", "Fast checks.", "Be quick.", null, HAIKU));

    private AssignmentHandler handler() {
        return new AssignmentHandler(new AssignmentHandler.Deliveries() {
            @Override
            public int deliver(Principal principal, SessionId session, SessionCreated create, Input input) {
                sent.add(new Sent(principal, session, create, input, null));
                return statuses.isEmpty() ? 200 : statuses.poll();
            }

            @Override
            public int cancel(Principal principal, SessionId session, String assignment) {
                sent.add(new Sent(principal, session, null, null, assignment));
                return statuses.isEmpty() ? 200 : statuses.poll();
            }
        }, agents, (user, session) -> running);
    }

    private static Assign start(String agent) {
        return new Assign(new CommandId("c1"), RUN, "t3-0", HELPER, agent, "Check module A.", "/home/alice/app", true, SONNET, Root.USER);
    }

    private Sent only() {
        assertEquals(1, sent.size(), sent.toString());
        assertEquals(ALICE, sent.getFirst().principal());
        return sent.getFirst();
    }

    @Test
    void anAssignmentCreatesItsHelperAndRunsOnTheModelOfTheRunThatGaveIt() throws Exception {
        assertNull(handler().handle(start(null)));

        Sent delivered = only();
        assertEquals(HELPER, delivered.session());
        assertEquals(new SessionCreated(SessionKind.HELPER, "/home/alice/app", true, new HelperOf(ISSUER, "t3-0", null, null, null)),
                delivered.create());
        assertEquals(new Assignment("t3-0", ISSUER, Root.USER, "Check module A.", SONNET), delivered.input());
    }

    @Test
    void aNamedAgentBringsItsInstructionsToolsAndItsOwnModelIfItHasOne() throws Exception {
        handler().handle(start("reviewer"));
        assertEquals(new HelperOf(ISSUER, "t3-0", "reviewer", "You review code.", List.of("read_file", "grep")), only().create().helper());
        assertEquals(SONNET, ((Assignment) only().input()).model());

        sent.clear();
        handler().handle(start("quick"));
        assertEquals(HAIKU, ((Assignment) only().input()).model());
    }

    @Test
    void anUnknownAgentStartsNothingAndTheIssuerIsToldWhichThereAre() throws Exception {
        handler().handle(start("nope"));

        Sent delivered = only();
        assertEquals(ISSUER, delivered.session());
        assertNull(delivered.create());
        Report report = (Report) delivered.input();
        assertEquals("t3-0", report.assignment());
        assertEquals(AssignmentOutcome.FAILED, report.outcome());
        assertTrue(report.text().contains("\"nope\"") && report.text().contains("reviewer, quick"), report.text());
    }

    /** 多用户：帮手的编号已经是别人的，入口拒了。不往那里送任何东西，交代的人得到失败和原因。 */
    @Test
    void whenTheHelpersIdBelongsToSomeoneElseTheIssuerIsToldItDidNotStart() throws Exception {
        statuses.add(404);
        handler().handle(start(null));

        assertEquals(2, sent.size());
        Sent told = sent.get(1);
        assertEquals(ISSUER, told.session());
        Report report = (Report) told.input();
        assertEquals(AssignmentOutcome.FAILED, report.outcome());
        assertTrue(report.text().startsWith("Not started: the server could not open a session"), report.text());
    }

    @Test
    void aReportGoesBackToTheIssuerWithItsRoot() throws Exception {
        RunRef helperRun = new RunRef(ALICE, HELPER, RunId.of(HELPER, 5));
        assertNull(handler().handle(new ReturnReport(new CommandId("c2"), helperRun, ISSUER, "t3-0", AssignmentOutcome.COMPLETED,
                "Module A is fine.", Root.OUTSIDE)));

        Sent delivered = only();
        assertEquals(ISSUER, delivered.session());
        assertNull(delivered.create());
        assertEquals(new Report("t3-0", AssignmentOutcome.COMPLETED, "Module A is fine.", Root.OUTSIDE), delivered.input());
    }

    @Test
    void stoppingARunningHelperTellsItToStopAndOneThatIsNotGivesTheIssuerStoppedAtOnce() throws Exception {
        StopAssignment stop = new StopAssignment(new CommandId("c3"), RUN, "t3-0", HELPER);
        handler().handle(stop);
        assertEquals(new Sent(ALICE, HELPER, null, null, "t3-0"), only());

        // 没送到、或者结束了结果没送回：没有在跑的运行。直接给“已停止”，这样叫停一定能把那条记录清掉
        sent.clear();
        running = false;
        handler().handle(stop);
        assertEquals(ISSUER, only().session());
        assertEquals(new Report("t3-0", AssignmentOutcome.STOPPED, null, Root.USER), only().input());
    }

    /** 入口说环满了（429）：等一会儿再送，直到送进去。 */
    @Test
    void aFullRingIsWaitedOut() throws Exception {
        statuses.add(429);
        statuses.add(429);
        handler().handle(new ReturnReport(new CommandId("c2"), RUN, ISSUER, "t3-0", AssignmentOutcome.COMPLETED, "ok", Root.USER));
        assertEquals(3, sent.size());
        assertEquals(sent.get(0), sent.get(2));
    }

    @Test
    void aCommandThatFailsHasNoEventOfItsOwn() {
        assertNull(handler().failed(start(null), new IllegalStateException("boom")));
        assertTrue(sent.isEmpty());
    }
}
