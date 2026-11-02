package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static tantalum.kernel.LoopFixture.text;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.command.CallModel;
import tantalum.value.command.DoWork;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.StopReason;
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
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), text("x"), StopReason.END_TURN, "m", null));
        assertInstanceOf(DoWork.class, loop.onlyCommand());
        return loop;
    }

    private static WorkDone done(LoopFixture loop, String continueText) {
        return new WorkDone(loop.onlyCommandId(), "check", new Json.Null(), false, continueText);
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
}
