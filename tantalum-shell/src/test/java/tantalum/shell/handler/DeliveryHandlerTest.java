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
import tantalum.value.command.Acknowledge;
import tantalum.value.command.Deliver;
import tantalum.value.delivery.AssignmentParcel;
import tantalum.value.delivery.ReportParcel;
import tantalum.value.delivery.StopParcel;
import tantalum.value.event.Assignment;
import tantalum.value.event.AssignmentCancelled;
import tantalum.value.event.Delivered;
import tantalum.value.event.Event;
import tantalum.value.event.Input;
import tantalum.value.event.Refused;
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
 * 送达的处理器（delivery 需求 1–3；subagent 需求 1–3、6、8）：经入口往另一个会话放事件。帮手会话在这里建，出身这时定下；
 * 帮手用交代它的那一轮的模型，agent 另外指定时用它的。不重试、不等：一时放不进去（环满、还没有）什么都不做，等送方重送；
 * 确定不收的（未知 agent、编号已经是别人的、对方不收这种东西）回一条 {@link Refused} 作为这条命令的结果。
 */
class DeliveryHandlerTest {

    private static final Principal ALICE = new Principal("alice");
    private static final SessionId ISSUER = new SessionId("s1");
    private static final SessionId HELPER = new SessionId("s1-t3-0");
    private static final RunRef RUN = new RunRef(ALICE, ISSUER, RunId.of(ISSUER, 1));
    private static final RunRef HELPER_RUN = new RunRef(ALICE, HELPER, RunId.of(HELPER, 2));
    private static final ModelChoice SONNET = new ModelChoice("claude", "claude-sonnet-5-5", "medium");
    private static final ModelChoice HAIKU = new ModelChoice("claude", "claude-haiku-5-5", null);

    /** 入口收到的一次送达：{@code create} 是“不存在就这样建”。 */
    private record Sent(Principal principal, SessionId session, SessionCreated create, Event event) {}

    private final List<Sent> sent = new ArrayList<>();
    /** 入口接下来几次的回答；空了就是 200。 */
    private final Deque<Integer> statuses = new ArrayDeque<>();
    /** 已经有了的会话（重送时帮手已经建好）。 */
    private final java.util.Set<SessionId> existing = new java.util.HashSet<>();
    private final Agents agents = (user, dir) -> List.of(
            new Agents.Agent("reviewer", "Reviews code.", "You review code.", List.of("read_file", "grep"), null),
            new Agents.Agent("quick", "Fast checks.", "Be quick.", null, HAIKU));

    private DeliveryHandler handler() {
        return new DeliveryHandler(new DeliveryHandler.Deliveries() {
            @Override
            public int deliver(Principal principal, SessionId session, SessionCreated create, Input input) {
                sent.add(new Sent(principal, session, create, input));
                return statuses.isEmpty() ? 200 : statuses.poll();
            }

            @Override
            public int handOver(Principal principal, SessionId session, Event event) {
                sent.add(new Sent(principal, session, null, event));
                return statuses.isEmpty() ? 200 : statuses.poll();
            }

            @Override
            public boolean exists(Principal principal, SessionId session) {
                return existing.contains(session);
            }
        }, agents);
    }

    private static Deliver start(String agent) {
        return new Deliver(new CommandId("c1"), RUN, HELPER, new AssignmentParcel(new Assignment("t3-0", ISSUER, Root.USER, "Check module A.", SONNET),
                agent, "/home/alice/app", true));
    }

    private Sent only() {
        assertEquals(1, sent.size(), sent.toString());
        assertEquals(ALICE, sent.getFirst().principal());
        return sent.getFirst();
    }

    @Test
    void anAssignmentCreatesItsHelperAndRunsOnTheModelOfTheRunThatGaveIt() throws Exception {
        assertNull(handler().handle(start(null)), "nothing comes back: the helper confirms");

        Sent delivered = only();
        assertEquals(HELPER, delivered.session());
        assertEquals(new SessionCreated(SessionKind.HELPER, "/home/alice/app", true, new HelperOf(ISSUER, "t3-0", null, null, null)),
                delivered.create());
        assertEquals(new Assignment("t3-0", ISSUER, Root.USER, "Check module A.", SONNET), delivered.event());
    }

    @Test
    void aNamedAgentBringsItsInstructionsToolsAndItsOwnModelIfItHasOne() throws Exception {
        handler().handle(start("reviewer"));
        assertEquals(new HelperOf(ISSUER, "t3-0", "reviewer", "You review code.", List.of("read_file", "grep")), only().create().helper());
        assertEquals(SONNET, ((Assignment) only().event()).model());

        sent.clear();
        handler().handle(start("quick"));
        assertEquals(HAIKU, ((Assignment) only().event()).model());
    }

    @Test
    void anUnknownAgentIsRefusedAndTheReasonNamesTheOnesThereAre() throws Exception {
        Event result = handler().handle(start("nope"));

        assertTrue(sent.isEmpty(), "nothing is opened");
        Refused refused = (Refused) result;
        assertEquals("assign:t3-0", refused.id());
        assertTrue(refused.reason().contains("\"nope\"") && refused.reason().contains("reviewer, quick"), refused.reason());
    }

    /** 多用户：帮手的编号已经是别人的，入口拒了：不收（确定的），不往那里送任何东西。 */
    @Test
    void whenTheHelpersIdBelongsToSomeoneElseTheAssignmentIsRefused() throws Exception {
        statuses.add(404);
        Refused refused = (Refused) handler().handle(start(null));
        assertTrue(refused.reason().startsWith("Not started: the server could not open a session"), refused.reason());
    }

    /** 一时进不去（环满）不是不收：什么都不回，送方到时重送；处理器不在这里等。 */
    @Test
    void aFullRingIsLeftToTheSendersTimer() throws Exception {
        statuses.add(429);
        assertNull(handler().handle(start(null)));
        assertEquals(1, sent.size(), "one try, no waiting");
    }

    @Test
    void aReportAndAStopGoToTheirSessionsAndAMissingSessionIsRetriedNotRefused() throws Exception {
        Report report = new Report("t3-0", AssignmentOutcome.COMPLETED, "fine", Root.OUTSIDE, HELPER);
        assertNull(handler().handle(new Deliver(new CommandId("c2"), HELPER_RUN, ISSUER, new ReportParcel(report))));
        assertEquals(new Sent(ALICE, ISSUER, null, report), only());

        sent.clear();
        existing.add(HELPER);
        AssignmentCancelled stop = new AssignmentCancelled("t3-0", ISSUER);
        assertNull(handler().handle(new Deliver(new CommandId("c3"), RUN, HELPER, new StopParcel(stop))));
        assertEquals(new Sent(ALICE, HELPER, null, stop), only());

        // 索引晚一步时入口说 404：不当成不收，等重送
        sent.clear();
        statuses.add(404);
        assertNull(handler().handle(new Deliver(new CommandId("c4"), HELPER_RUN, ISSUER, new ReportParcel(report))));
        statuses.add(409);
        assertEquals(new Refused("report:t3-0", "that session does not take results"),
                handler().handle(new Deliver(new CommandId("c5"), HELPER_RUN, ISSUER, new ReportParcel(report))));
    }

    @Test
    void aConfirmationOrARefusalGoesBackToTheSender() throws Exception {
        assertNull(handler().handle(new Acknowledge(new CommandId("c6"), HELPER_RUN, "assign:t3-0", ISSUER, null)));
        assertEquals(new Sent(ALICE, ISSUER, null, new Delivered("assign:t3-0")), only());

        sent.clear();
        statuses.add(429);
        assertNull(handler().handle(new Acknowledge(new CommandId("c7"), HELPER_RUN, "stop:t3-0", ISSUER, "not this session's")));
        assertEquals(new Sent(ALICE, ISSUER, null, new Refused("stop:t3-0", "not this session's")), only(), "tried once; lost ones are re-sent by the sender");
    }

    /** 重送：帮手已经建好了，不再读 agent（连接程序一时断开不能把一次已经送到的交代变成“不收”），直接送，帮手认出重复。 */
    @Test
    void aResentAssignmentToAnExistingHelperDoesNotLookTheAgentUpAgain() throws Exception {
        existing.add(HELPER);
        assertNull(handler().handle(start("nope")), "not refused although the agent cannot be found now");
        Sent resent = only();
        assertNull(resent.create());
        assertEquals(new Assignment("t3-0", ISSUER, Root.USER, "Check module A.", SONNET), resent.event());
    }

    @Test
    void aCommandThatFailsHasNoEventOfItsOwn() {
        assertNull(handler().failed(start(null), new IllegalStateException("boom")));
    }
}
