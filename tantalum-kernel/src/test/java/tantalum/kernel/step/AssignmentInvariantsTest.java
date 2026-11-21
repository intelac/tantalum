package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.Replay;
import tantalum.value.event.ModelFailed;
import tantalum.value.model.FailureReason;
import tantalum.value.event.Report;
import tantalum.value.event.Root;
import tantalum.value.state.RunCause;
import tantalum.value.state.SessionKind;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.kernel.step.AssignmentViews.ReturnReport;
import tantalum.kernel.step.AssignmentViews.Assign;
import tantalum.value.command.StartTimer;
import tantalum.kernel.step.AssignmentViews.StopAssignment;
import tantalum.value.decision.Fact;
import tantalum.value.decision.ReportSettled;
import tantalum.value.decision.AssignmentStarted;
import tantalum.value.event.Cancel;
import tantalum.value.event.ContextAssembledFixture;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.RunInterrupted;
import tantalum.value.event.AssignmentCancelled;
import tantalum.value.event.StopRequested;
import tantalum.value.event.TimerFired;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tantalum.value.assignment.OpenAssignment;
import tantalum.value.assignment.AssignmentLimits;
import tantalum.value.assignment.AssignmentOutcome;
import tantalum.value.assignment.AssignmentTools;

/**
 * 任务的状态闭环（subagent 需求 4），用随机的事件顺序去撞：一个放任务的会话和它放出的任务的会话，命令的结果乱序到、
 * 重复到、迟到；中途有人说话、有人停任务、主对话被撤销或被重启打断、模型调用失败、任务被重启打断、期限的定时器早到晚到。不管顺序怎样：
 *
 * <ul>
 * <li>内核一步也不抛异常；</li>
 * <li>名单里没有重复的任务，也没有不是它放出的任务；</li>
 * <li>全部结果都送到以后，名单是空的，放出的每个任务恰好有一个生效的结果，这个结果在历史里恰好出现一次；</li>
 * <li>收件队列最后是空的：没有哪条输入卡在里面；</li>
 * <li>把同一串事件再喂一遍，状态完全相同。</li>
 * </ul>
 */
class AssignmentInvariantsTest {

    /** 一条还没给结果的命令：外壳会在某个时刻把结果送回来，这里由随机数决定是哪一个、什么时候。 */
    private final List<Command> pending = new ArrayList<>();
    private int seen;

    private void collect(LoopFixture loop) {
        for (; seen < loop.commands.size(); seen++) {
            Command command = loop.commands.get(seen);
            if (command instanceof AssembleContext || command instanceof CallModel || command instanceof tantalum.value.command.Deliver
                    || command instanceof tantalum.value.command.Acknowledge || command instanceof StartTimer) {
                pending.add(command);
            }
        }
    }

    private static ToolCallBlock task(String id, String prompt) {
        return AssignmentFixture.task(id, prompt);
    }

    private static boolean running(LoopFixture loop, SessionId session) {
        SessionState state = loop.state.sessions().get(session);
        return state != null && state.activeRun() != null;
    }

    /** 外壳对一条命令的反应。{@code again} 为真时结果送两次（重复送达）。 */
    private void deliver(LoopFixture loop, Command command, Random random, int[] budget) {
        SessionId session = command.run().session();
        RunId run = command.run().run();
        switch (command) {
            case AssembleContext assemble -> loop.feed(session, run, ContextAssembledFixture.of(assemble.id(), "s", List.of(), null, List.of()));
            case CallModel call -> {
                boolean parent = session.equals(LoopFixture.SESSION);
                List<ContentBlock> content = new ArrayList<>();
                // 任务的会话也会试着放任务（内核拦着）；放任务的会话有时放一两个，有时停一个，有时说完；偶尔这次调用失败
                int roll = random.nextInt(10);
                if (roll == 9) {
                    loop.feed(session, run, new ModelFailed(call.id(), FailureReason.REFUSAL, false, null, null, "refused", null, null, null));
                    return;
                }
                if (roll < 4 && budget[0] > 0) {
                    int count = 1 + random.nextInt(2);
                    for (int i = 0; i < count; i++) {
                        content.add(task("call-" + budget[0] + "-" + i, "job " + budget[0] + "-" + i));
                    }
                    budget[0]--;
                } else if (roll < 5 && parent && !loop.session().assignments().open().isEmpty()) {
                    OpenAssignment open = loop.session().assignments().open().get(random.nextInt(loop.session().assignments().open().size()));
                    content.add(new ToolCallBlock("stop-" + command.id().value(), AssignmentTools.TASK_STOP, "{}",
                            new Json.Obj(Map.of(AssignmentTools.ID, new Json.Str(open.id())))));
                } else {
                    content.add(new TextBlock("done " + command.id().value()));
                }
                boolean tools = content.stream().anyMatch(ToolCallBlock.class::isInstance);
                loop.feed(session, run, new ModelReplied(call.id(), content, tools ? StopReason.TOOL_USE : StopReason.END_TURN, "m", null, null, null, null));
            }
            // 和外壳的处理器一样送达（delivery）；有时丢掉（等确认定时器重送），有时送两次
            case tantalum.value.command.Deliver deliver -> {
                if (random.nextInt(6) == 0) {
                    return;
                }
                tantalum.value.event.Event what = switch (deliver.what()) {
                    case tantalum.value.delivery.AssignmentParcel given -> {
                        Assign start = (Assign) AssignmentFixture.view(deliver);
                        loop.create(start.session(), AssignmentFixture.helperFor(start));
                        yield AssignmentFixture.assignmentOf(start);
                    }
                    case tantalum.value.delivery.ReportParcel back -> back.input();
                    case tantalum.value.delivery.StopParcel stop -> stop.input();
                };
                loop.feed(deliver.to(), null, what);
                if (random.nextInt(5) == 0) {
                    again(loop, deliver.to(), what);
                }
            }
            // 回执：有时丢掉（送方到时重送，收方认出重复再回）
            case tantalum.value.command.Acknowledge ack -> {
                if (random.nextInt(6) == 0) {
                    return;
                }
                loop.feed(ack.to(), null, ack.refused() == null ? new tantalum.value.event.Delivered(ack.delivery())
                        : new tantalum.value.event.Refused(ack.delivery(), ack.refused()));
            }
            case StartTimer timer -> loop.feed(session, run, new TimerFired(timer.id()));
            default -> throw new IllegalStateException("unexpected " + command);
        }
    }

    /** 同一个事件再送一次（重复送达）：什么都不改，只回“送到了”，不报事实。 */
    private static void again(LoopFixture loop, SessionId session, tantalum.value.event.Event event) {
        tantalum.value.state.KernelState before = loop.state;
        tantalum.value.decision.Decision decision = loop.feed(session, null, event);
        assertSame(before, decision.newState(), "a repeated " + event.type() + " changed the state");
        assertTrue(decision.commands().stream().allMatch(tantalum.value.command.Acknowledge.class::isInstance) && decision.facts().isEmpty(),
                "a repeated " + event.type() + " did something: " + decision.commands());
    }

    /** 不由命令引起的事：人说话（排队或并进这一轮）、人停任务、撤销主对话、重启打断主对话或一个任务。 */
    private void disturb(LoopFixture loop, Random random) {
        switch (random.nextInt(7)) {
            case 0, 1 -> loop.prompt("more " + random.nextInt(1000));
            case 5 -> loop.join("and " + random.nextInt(1000));
            case 6 -> {
                if (running(loop, LoopFixture.SESSION)) {
                    loop.feed(loop.session().activeRun(), new RunInterrupted());
                }
            }
            case 2 -> loop.feed(null, new StopRequested(new RequestId("r" + random.nextInt(1000)),
                    loop.session() == null || loop.session().assignments().open().isEmpty() || random.nextBoolean() ? null
                            : loop.session().assignments().open().getFirst().id()));
            case 3 -> {
                if (running(loop, LoopFixture.SESSION)) {
                    loop.feed(loop.session().activeRun(), new Cancel(new RequestId("c" + random.nextInt(1000))));
                }
            }
            default -> {
                List<SessionId> children = loop.state.sessions().keySet().stream().filter(id -> !id.equals(LoopFixture.SESSION))
                        .filter(id -> running(loop, id)).sorted(java.util.Comparator.comparing(SessionId::value)).toList();
                if (!children.isEmpty()) {
                    SessionId child = children.get(random.nextInt(children.size()));
                    loop.feed(child, loop.state.sessions().get(child).activeRun(), new RunInterrupted());
                }
            }
        }
    }

    private static void checkList(LoopFixture loop) {
        SessionState parent = loop.session();
        if (parent == null) {
            return;
        }
        List<String> open = parent.assignments().open().stream().map(OpenAssignment::id).toList();
        assertEquals(new HashSet<>(open).size(), open.size(), "a task is listed twice: " + open);
        for (SessionState session : loop.state.sessions().values()) {
            if (!session.id().equals(LoopFixture.SESSION)) {
                assertTrue(session.assignments().open().isEmpty(), "a task started tasks: " + session.id().value());
                assertEquals(SessionKind.HELPER, session.kind());
                assertEquals(LoopFixture.SESSION, session.helper().issuer());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60})
    void whateverTheOrderEveryTaskGetsExactlyOneResultAndReplayAgrees(int seed) {
        Random random = new Random(seed);
        // 上限和期限也跟着种子变：有的种子没有期限，有的很紧
        AssignmentLimits limits = new AssignmentLimits(1 + random.nextInt(4), random.nextInt(6), 2 + random.nextInt(10),
                random.nextInt(3) == 0 ? Duration.ofMinutes(5) : null);
        LoopFixture loop = new LoopFixture(10, limits);
        int[] budget = {8};
        Set<String> started = new HashSet<>();
        Map<String, Integer> settled = new HashMap<>();
        loop.prompt("go");
        for (int step = 0; step < 600; step++) {
            collect(loop);
            if (pending.isEmpty() && step >= 40) {
                break;
            }
            // 前 40 步里夹着外面来的事；之后只把欠着的结果送完
            if (step < 40 && (pending.isEmpty() || random.nextInt(4) == 0)) {
                disturb(loop, random);
            } else if (!pending.isEmpty()) {
                deliver(loop, pending.remove(random.nextInt(pending.size())), random, budget);
            }
            checkList(loop);
        }
        collect(loop);
        assertTrue(pending.isEmpty(), "seed " + seed + " did not settle: " + pending);

        // 同一串事件再喂一遍：状态和命令完全相同。事实也从这一遍里取（迟到的、重复的事件不产生事实，数到的都是生效的）
        Replay.Result again = Replay.run(loop.kernel, loop.journal);
        assertEquals(loop.state, again.state(), "seed " + seed);
        assertEquals(loop.commands, again.commands(), "seed " + seed);
        for (Fact fact : again.facts()) {
            if (fact instanceof AssignmentStarted start) {
                assertTrue(started.add(start.assignment()), "seed " + seed + ": the same task id was started twice: " + start.assignment());
            } else if (fact instanceof ReportSettled done) {
                settled.merge(done.assignment(), 1, Integer::sum);
            }
        }

        // 全部结果都送到了：名单空了，每个任务恰好一个结果，没有哪一轮还开着
        assertTrue(loop.session().assignments().open().isEmpty(), "seed " + seed + " left tasks out: " + loop.session().assignments().open());
        assertEquals(started, settled.keySet(), "seed " + seed);
        settled.forEach((task, times) -> assertEquals(1, times, "seed " + seed + ": task " + task + " was settled " + times + " times"));
        for (SessionState session : loop.state.sessions().values()) {
            assertNull(session.activeRun(), "seed " + seed + ": a run is still open in " + session.id().value());
            // 送出去的都确认了（丢了的由确认定时器重送）
            assertTrue(session.outbox().isEmpty(), "seed " + seed + ": unconfirmed in " + session.id().value() + ": " + session.outbox());
            // 没有哪条输入还卡在收件队列里：要么被哪一轮看到了，要么记进了历史
            assertTrue(session.inbox().isEmpty(), "seed " + seed + ": left in the inbox of " + session.id().value() + ": " + session.inbox());
        }
        // 每个生效的结果在交代的会话的历史里恰好出现一次
        Map<String, Integer> shown = new HashMap<>();
        for (tantalum.value.model.Message message : loop.session().messages()) {
            for (ContentBlock block : message.content()) {
                if (block instanceof tantalum.value.model.ReportBlock result) {
                    shown.merge(result.assignment(), 1, Integer::sum);
                }
            }
        }
        assertEquals(started, shown.keySet(), "seed " + seed);
        shown.forEach((task, times) -> assertEquals(1, times, "seed " + seed + ": the result of " + task + " is in the history " + times + " times"));
        for (RunState run : loop.state.runs().values()) {
            assertTrue(run.isEnded(), "seed " + seed + ": " + run.ref().run().value() + " is " + run.phase().name());
        }

    }
}
