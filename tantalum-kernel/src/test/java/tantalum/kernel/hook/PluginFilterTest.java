package tantalum.kernel.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static tantalum.kernel.LoopFixture.call;
import static tantalum.kernel.LoopFixture.text;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.command.CallModel;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.model.StopReason;
import tantalum.value.state.KernelState;

/** core-tools C16、需求 8 第 5、6 条：钩子按会话记下的插件名单过滤。 */
class PluginFilterTest {

    /** 记下每次被调用的位置，属于插件 notes。 */
    static final class Notes implements KernelHook {
        final List<String> calls = new ArrayList<>();
        @Override public String name() { return "notesHook"; }
        @Override public String plugin() { return "notes"; }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
            calls.add("beforeModelCall");
            return Allow.PLAIN;
        }
        @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
            calls.add("beforeToolCall");
            return Allow.PLAIN;
        }
    }

    private static void assemble(LoopFixture loop, List<String> plugins) {
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", List.of(), null, List.of(), List.of(), plugins));
    }

    private static void endTurn(LoopFixture loop) {
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), text("ok"), StopReason.END_TURN, "m", null, null, null, null));
    }

    @Test
    void aTurnedOffPluginsHooksDoNotRun() {
        Notes notes = new Notes();
        LoopFixture loop = new LoopFixture(notes);
        loop.prompt("q1");
        assemble(loop, List.of());
        assertInstanceOf(CallModel.class, loop.onlyCommand(), "the run goes on without the plugin");
        assertEquals(List.of(), notes.calls);
    }

    @Test
    void aTurnedOnPluginsHooksRun() {
        Notes notes = new Notes();
        LoopFixture loop = new LoopFixture(notes);
        loop.prompt("q1");
        assemble(loop, List.of("notes"));
        assertEquals(List.of("beforeModelCall"), notes.calls);
    }

    /** 名单只在运行开始时换：这次运行一直按开始时的名单跑，下一次提问才按新的。 */
    @Test
    void theListHoldsForTheWholeRun() {
        Notes notes = new Notes();
        LoopFixture loop = new LoopFixture(notes);
        loop.prompt("q1");
        assemble(loop, List.of("notes"));
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), List.of(call("c1", "grep")), StopReason.TOOL_USE, "m", null,
                null, null, null));
        loop.feed(loop.runId(), new ToolFinished(loop.onlyCommandId(), "c1", "found", false, null));
        endTurn(loop);
        assertEquals(List.of("beforeModelCall", "beforeToolCall", "beforeModelCall"), notes.calls);

        notes.calls.clear();
        loop.prompt("q2");
        assemble(loop, List.of());
        endTurn(loop);
        assertEquals(List.of(), notes.calls);
    }

    /** 加名单之前的会话：名单为空，全部照跑。 */
    @Test
    void sessionsWithoutAListRunEveryHook() {
        Notes notes = new Notes();
        LoopFixture loop = new LoopFixture(notes);
        loop.prompt("q1");
        assemble(loop, null);
        assertEquals(List.of("beforeModelCall"), notes.calls);
    }
}
