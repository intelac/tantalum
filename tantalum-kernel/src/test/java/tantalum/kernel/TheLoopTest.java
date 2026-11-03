package tantalum.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static tantalum.kernel.LoopFixture.call;
import static tantalum.kernel.LoopFixture.text;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.DoWork;
import tantalum.value.command.RunTool;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.json.Json;
import tantalum.value.model.StopReason;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;

/** 设计图上的十圈。任何一条回边被改坏，都会在具体的一圈失败。 */
class TheLoopTest {

    private static final KernelHook CITATION_CHECK = new KernelHook() {
        @Override public String name() { return "citationCheck"; }
        @Override public HookResult beforeRunEnd(KernelState state, HookContext context) {
            return context.work() == null ? new NeedWork("citation", new Json.Null()) : Allow.PLAIN;
        }
    };

    static LoopFixture runTenTurns() {
        LoopFixture loop = new LoopFixture(CITATION_CHECK);

        // 第 1 圈
        loop.prompt("where is the interest calculated?");
        assertEquals("AwaitingContext", loop.phase(), "turn 1");
        assertInstanceOf(AssembleContext.class, loop.onlyCommand(), "turn 1");

        // 第 2 圈
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), null, List.of()));
        assertEquals("AwaitingModel", loop.phase(), "turn 2");
        assertInstanceOf(CallModel.class, loop.onlyCommand(), "turn 2");

        // 第 3 圈
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(),
                List.of(call("1", "symbol_search")), StopReason.TOOL_USE, "m", null, null, null, null));
        assertEquals("AwaitingTools", loop.phase(), "turn 3");
        RunTool search = assertInstanceOf(RunTool.class, loop.onlyCommand(), "turn 3");

        // 第 4 圈：工具错误，索引还在建
        loop.feed(loop.runId(), new ToolFinished(search.id(), "1", "index still building", true, null));
        assertEquals("AwaitingModel", loop.phase(), "turn 4");
        assertInstanceOf(CallModel.class, loop.onlyCommand(), "turn 4");

        // 第 5 圈
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(),
                List.of(call("2", "grep")), StopReason.TOOL_USE, "m", null, null, null, null));
        assertEquals("AwaitingTools", loop.phase(), "turn 5");
        RunTool grep = assertInstanceOf(RunTool.class, loop.onlyCommand(), "turn 5");

        // 第 6 圈
        loop.feed(loop.runId(), new ToolFinished(grep.id(), "2", "INTEREST.cbl:42", false, null));
        assertEquals("AwaitingModel", loop.phase(), "turn 6");
        assertInstanceOf(CallModel.class, loop.onlyCommand(), "turn 6");

        // 第 7 圈
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(),
                List.of(call("3", "read_symbol")), StopReason.TOOL_USE, "m", null, null, null, null));
        assertEquals("AwaitingTools", loop.phase(), "turn 7");
        RunTool read = assertInstanceOf(RunTool.class, loop.onlyCommand(), "turn 7");

        // 第 8 圈
        loop.feed(loop.runId(), new ToolFinished(read.id(), "3", "COMPUTE INTEREST ...", false, null));
        assertEquals("AwaitingModel", loop.phase(), "turn 8");
        assertInstanceOf(CallModel.class, loop.onlyCommand(), "turn 8");

        // 第 9 圈：回答完毕，beforeRunEnd 要求引用核对
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(),
                text("It is in INTEREST.cbl:42"), StopReason.END_TURN, "m", null, null, null, null));
        assertEquals("AwaitingWork", loop.phase(), "turn 9");
        DoWork citation = assertInstanceOf(DoWork.class, loop.onlyCommand(), "turn 9");
        assertEquals("citation", citation.workType(), "turn 9");

        // 第 10 圈
        loop.feed(loop.runId(), new WorkDone(citation.id(), "citation", new Json.Null(), false, null, null));
        assertEquals(EndReason.COMPLETED, assertInstanceOf(Ended.class, loop.run().phase(), "turn 10").reason(), "turn 10");
        assertEquals(List.of(), loop.last.commands(), "turn 10");

        return loop;
    }

    @Test
    void tenTurns() {
        runTenTurns();
    }
}
