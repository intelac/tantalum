package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static tantalum.kernel.LoopFixture.call;
import static tantalum.kernel.LoopFixture.text;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.Replay;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.DoWork;
import tantalum.value.command.RunTool;
import tantalum.value.decision.HookReported;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Message;
import tantalum.value.model.StopReason;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;

class WorkStepsTest {

    /** 没有工作结果时要求核对，看到工作结果就放行。 */
    private static final KernelHook CHECK_ONCE = new KernelHook() {
        @Override public String name() { return "check"; }
        @Override public HookResult beforeRunEnd(KernelState state, HookContext context) {
            return context.work() == null ? new NeedWork("check", new Json.Null()) : Allow.PLAIN;
        }
    };

    private static LoopFixture atWork(KernelHook... hooks) {
        LoopFixture loop = new LoopFixture(hooks);
        loop.prompt("hi");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", List.of(), null, List.of()));
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), text("x"), StopReason.END_TURN, "m", null, null, null, null));
        assertInstanceOf(DoWork.class, loop.onlyCommand());
        return loop;
    }

    private static WorkDone done(LoopFixture loop, String continueText) {
        return new WorkDone(loop.onlyCommandId(), "check", new Json.Null(), false, continueText, null);
    }

    @Test
    void workFromRunEndGoesBackThroughBeforeRunEnd() {
        LoopFixture loop = atWork(CHECK_ONCE);
        loop.feed(loop.runId(), done(loop, null));
        assertEquals(EndReason.COMPLETED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
    }

    @Test
    void beforeRunEndMayAskForMoreWork() {
        KernelHook always = new KernelHook() {
            @Override public String name() { return "always"; }
            @Override public HookResult beforeRunEnd(KernelState state, HookContext context) {
                return new NeedWork("check", new Json.Null());
            }
        };
        LoopFixture loop = atWork(always);
        loop.feed(loop.runId(), done(loop, null));
        assertEquals("AwaitingWork", loop.phase());
    }

    @Test
    void continueTextGoesBackToTheModel() {
        LoopFixture loop = atWork(CHECK_ONCE);
        loop.feed(loop.runId(), done(loop, "citation wrong, fix it"));
        assertEquals("AwaitingModel", loop.phase());
        assertEquals(Message.user("citation wrong, fix it"),
                assertInstanceOf(CallModel.class, loop.onlyCommand()).prompt().messages().getLast());
    }

    @Test
    void onWorkResultStopEndsTheRun() {
        KernelHook stop = new KernelHook() {
            @Override public String name() { return "stop"; }
            @Override public HookResult beforeRunEnd(KernelState state, HookContext context) {
                return new NeedWork("check", new Json.Null());
            }
            @Override public HookResult onWorkResult(KernelState state, HookContext context) {
                return new Stop("bad");
            }
        };
        LoopFixture loop = atWork(stop);
        loop.feed(loop.runId(), done(loop, null));
        assertEquals(EndReason.STOPPED, assertInstanceOf(Ended.class, loop.run().phase()).reason());
    }

    @Test
    void onWorkResultNeedWorkKeepsWorking() {
        KernelHook again = new KernelHook() {
            @Override public String name() { return "again"; }
            @Override public HookResult beforeRunEnd(KernelState state, HookContext context) {
                return new NeedWork("check", new Json.Null());
            }
            @Override public HookResult onWorkResult(KernelState state, HookContext context) {
                return new NeedWork("second", new Json.Null());
            }
        };
        LoopFixture loop = atWork(again);
        loop.feed(loop.runId(), done(loop, null));
        assertEquals("second", assertInstanceOf(DoWork.class, loop.onlyCommand()).workType());
    }

    // ---- 工作轮数上限：默认每个等待点连续 8 轮，第 9 次要求工作时运行以失败结束 ----

    private static NeedWork round(String tool, int n) {
        return new NeedWork("w", new Json.Obj(Map.of("tool", new Json.Str(tool), "n", new Json.Num(BigDecimal.valueOf(n)))));
    }

    private static String toolOf(HookContext context) {
        return ((Json.Str) ((Json.Obj) context.work().result()).fields().get("tool")).value();
    }

    private static int roundOf(HookContext context) {
        return ((Json.Num) ((Json.Obj) context.work().result()).fields().get("n")).value().intValue();
    }

    /** 提交一条消息并装好上下文，之后内核要么调模型，要么因钩子先去做工作。 */
    private static LoopFixture started(KernelHook... hooks) {
        LoopFixture loop = new LoopFixture(hooks);
        loop.prompt("hi");
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", List.of(), null, List.of()));
        return loop;
    }

    /**
     * 按脚本驱动到运行结束：工作把输入原样作为结果送回（带 {@code continueText}），工具都成功，
     * 模型依次取 {@code replies}，取完后回一段文字。最多 40 步，没有上限时不会死循环，而是断言失败。
     */
    @SafeVarargs
    private static void drive(LoopFixture loop, String continueText, List<ContentBlock>... replies) {
        Deque<List<ContentBlock>> scripted = new ArrayDeque<>(List.of(replies));
        for (int step = 0; step < 40 && !loop.run().isEnded(); step++) {
            Command command = loop.onlyCommand();
            switch (command) {
                case DoWork work -> loop.feed(loop.runId(), new WorkDone(work.id(), work.workType(), work.input(), false, continueText, null));
                case CallModel model -> loop.feed(loop.runId(), new ModelReplied(model.id(),
                        scripted.isEmpty() ? text("done") : scripted.poll(), StopReason.END_TURN, "m", null, null, null, null));
                case RunTool tool -> loop.feed(loop.runId(), new ToolFinished(tool.id(), tool.call().id(), "ok", false, null));
                default -> fail("unexpected command " + command);
            }
        }
    }

    private static long count(LoopFixture loop, Class<? extends Command> type) {
        return loop.commands.stream().filter(type::isInstance).count();
    }

    /** 8 轮工作照常发出，第 9 次要求时失败：写明钩子、位置和轮数，报钩子事实，不再发工作；回放得到同样的状态。 */
    private static void assertGaveUp(LoopFixture loop, String hook, HookPoint at) {
        Ended ended = assertInstanceOf(Ended.class, loop.run().phase(), "run should have ended");
        assertEquals(EndReason.FAILED, ended.reason());
        assertEquals("plugin work did not settle: " + hook + " at " + at + " after 8 rounds (limit 8)", ended.detail());
        assertTrue(loop.last.facts().contains(new HookReported(loop.run().ref(), hook, "asked for more work after 8 rounds in a row at " + at + " (limit 8)")),
                loop.last.facts().toString());
        assertTrue(loop.last.commands().stream().noneMatch(DoWork.class::isInstance));
        assertEquals(8, count(loop, DoWork.class));
        assertEquals(loop.state, Replay.run(loop.kernel, loop.journal).state());
    }

    private static void assertCompleted(LoopFixture loop) {
        assertEquals(EndReason.COMPLETED, assertInstanceOf(Ended.class, loop.run().phase(), "run should have ended").reason());
        assertEquals(loop.state, Replay.run(loop.kernel, loop.journal).state());
    }

    @Test
    void beforeModelCallGivesUpAfterTheCap() {
        KernelHook model = new KernelHook() {
            @Override public String name() { return "model"; }
            @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
                return round("-", 0);
            }
        };
        LoopFixture loop = started(model);
        drive(loop, null);
        assertGaveUp(loop, "model", HookPoint.BEFORE_MODEL_CALL);
        assertEquals(0, count(loop, CallModel.class));
    }

    /** 工具调用前每个工具只问一次，审批一直待定靠 {@code onWorkResult} 再要；失败结束时等审批的调用由 {@code RunEnd.finish} 补上结果。 */
    @Test
    void beforeToolCallGivesUpAfterTheCap() {
        KernelHook tool = new KernelHook() {
            @Override public String name() { return "tool"; }
            @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
                return round(context.call().id(), 0);
            }
            @Override public HookResult onWorkResult(KernelState state, HookContext context) {
                return round(toolOf(context), roundOf(context) + 1);
            }
        };
        LoopFixture loop = started(tool);
        drive(loop, null, List.of(call("t1", "x")));
        assertGaveUp(loop, "tool", HookPoint.BEFORE_TOOL_CALL);
        Message last = loop.session().messages().getLast();
        assertEquals("t1", assertInstanceOf(ToolResultBlock.class, last.content().getFirst()).toolCallId());
    }

    /** 结束前的工作每圈都带着续写回到模型；模型回复不清零结束前的计数，所以还是数得住。 */
    @Test
    void beforeRunEndCountsAcrossModelReplies() {
        KernelHook end = new KernelHook() {
            @Override public String name() { return "end"; }
            @Override public HookResult beforeRunEnd(KernelState state, HookContext context) {
                return round("-", 0);
            }
        };
        LoopFixture loop = started(end);
        drive(loop, "check again");
        assertGaveUp(loop, "end", HookPoint.BEFORE_RUN_END);
        assertEquals(9, count(loop, CallModel.class));
    }

    /** {@code onWorkResult} 接着要的工作计在原来的等待点上；原因里的钩子是最后要工作的那个。 */
    @Test
    void onWorkResultCountsOnTheOriginalPoint() {
        KernelHook approval = new KernelHook() {
            @Override public String name() { return "approval"; }
            @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
                return round(context.call().id(), 0);
            }
        };
        KernelHook poller = new KernelHook() {
            @Override public String name() { return "poller"; }
            @Override public HookResult onWorkResult(KernelState state, HookContext context) {
                return round(toolOf(context), roundOf(context) + 1);
            }
        };
        LoopFixture loop = started(approval, poller);
        drive(loop, null, List.of(call("t1", "x")));
        assertGaveUp(loop, "poller", HookPoint.BEFORE_TOOL_CALL);

        KernelHook once = new KernelHook() {
            @Override public String name() { return "once"; }
            @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
                return context.work() == null ? round("-", 0) : Allow.PLAIN;
            }
        };
        LoopFixture model = started(once, poller);
        drive(model, null);
        assertGaveUp(model, "poller", HookPoint.BEFORE_MODEL_CALL);
    }

    /** 失败后的工作受重试次数限制，但同样计数；接着要的工作计在失败后这个位置上。 */
    @Test
    void onFailureWorkIsCountedToo() {
        KernelHook status = new KernelHook() {
            @Override public String name() { return "status"; }
            @Override public HookResult onFailure(KernelState state, HookContext context) {
                return round("-", 0);
            }
            @Override public HookResult onWorkResult(KernelState state, HookContext context) {
                return round("-", roundOf(context) + 1);
            }
        };
        LoopFixture loop = started(status);
        loop.feed(loop.runId(), new ModelFailed(loop.onlyCommandId(), FailureReason.SERVER_ERROR, true, null, "", "503", null, null, null));
        drive(loop, null);
        assertGaveUp(loop, "status", HookPoint.ON_FAILURE);
    }

    /** 模型调用前每次要 5 轮：模型回复清零，两次调用共 10 轮也不失败。 */
    @Test
    void modelReplyResetsTheCount() {
        KernelHook model = new KernelHook() {
            @Override public String name() { return "model"; }
            @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
                if (context.work() == null) {
                    return round("-", 1);
                }
                return roundOf(context) < 5 ? round("-", roundOf(context) + 1) : Allow.PLAIN;
            }
        };
        LoopFixture loop = started(model);
        drive(loop, null, List.of(call("t1", "x")));
        assertCompleted(loop);
        assertEquals(10, count(loop, DoWork.class));
        assertEquals(2, count(loop, CallModel.class));
    }

    /** 两个工具各要 5 轮：一个被拦下、一个发出去，换下一个工具时清零，互不影响。 */
    @Test
    void eachToolCallCountsOnItsOwn() {
        KernelHook approval = new KernelHook() {
            @Override public String name() { return "approval"; }
            @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
                return round(context.call().id(), 1);
            }
            @Override public HookResult onWorkResult(KernelState state, HookContext context) {
                if (roundOf(context) < 5) {
                    return round(toolOf(context), roundOf(context) + 1);
                }
                return toolOf(context).equals("t1") ? new Stop("denied") : Allow.PLAIN;
            }
        };
        LoopFixture loop = started(approval);
        drive(loop, null, List.of(call("t1", "x"), call("t2", "x")));
        assertCompleted(loop);
        assertEquals(10, count(loop, DoWork.class));
        List<String> ran = new ArrayList<>();
        loop.commands.stream().filter(RunTool.class::isInstance).forEach(tool -> ran.add(((RunTool) tool).call().id()));
        assertEquals(List.of("t2"), ran);
    }

    /** 引用核对每次结束前只要 1 轮；每个运行从 0 开始数，同一会话连续 10 个运行都照常完成。 */
    @Test
    void citationCheckIsUnaffected() {
        LoopFixture loop = new LoopFixture(CHECK_ONCE);
        for (int i = 0; i < 10; i++) {
            loop.prompt("hi " + i);
            loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", List.of(), null, List.of()));
            drive(loop, null);
            assertCompleted(loop);
        }
        assertEquals(10, count(loop, DoWork.class));
    }
}
