package tantalum.shell.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static tantalum.shell.trace.TraceFixture.badCall;
import static tantalum.shell.trace.TraceFixture.call;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.tool.ToolPairing;
import tantalum.shell.trace.TraceRecord.HookDecision;
import tantalum.shell.trace.TraceRecord.ToolCall;
import tantalum.shell.trace.TraceRecord.ToolCall.Status;
import tantalum.value.event.Cancel;
import tantalum.value.event.RunInterrupted;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.state.KernelState;

/** 工具记录的每种状态：等待、运行中、完成、出错、被拦下（钩子 / 参数无效）、没有发出、结果不明（取消 / 重启），以及并行组和 MCP 拆名。 */
class TraceToolTest {

    private static final KernelHook NO_RM = new KernelHook() {
        @Override public String name() { return "no-rm"; }
        @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
            return context.call().name().equals("rm") ? new Stop("destructive") : KernelHook.super.beforeToolCall(state, context);
        }
    };

    private static final KernelHook THROWS = new KernelHook() {
        @Override public String name() { return "throws"; }
        @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
            throw new IllegalStateException("bad hook");
        }
    };

    private static ToolCall tool(TraceFixture fx, String callId) {
        return fx.records(ToolCall.class).stream().filter(tool -> tool.call().equals(callId)).findFirst().orElseThrow();
    }

    @Test
    void parallelCallsShareAGroupAndKeepTheirOwnTimesAndRawErrorText() {
        TraceFixture fx = new TraceFixture(true, List.of());
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(call("c1", "read_file"), call("c2", "grep")));
        ToolCall first = tool(fx, "c1");
        assertEquals(Status.RUNNING, first.status());
        assertEquals("t:2.0", first.id());
        assertEquals(first.group(), tool(fx, "c2").group());
        assertEquals("r:s_1-0", first.request());
        assertEquals(1, first.head().step());

        fx.feed(run, fx.finished(run, "c1", "body", false));
        fx.feed(run, fx.finished(run, "c2", "  \n", true));

        assertEquals(Status.DONE, tool(fx, "c1").status());
        assertEquals(fx.steps.get(3).envelope().at(), tool(fx, "c1").startedAt());
        assertEquals(fx.steps.get(3).envelope().at(), tool(fx, "c1").head().end());
        ToolCall failed = tool(fx, "c2");
        assertEquals(Status.ERROR, failed.status());
        assertEquals("  \n", failed.result());
        assertNull(failed.code());
    }

    @Test
    void exclusiveCallsWaitAndRunInTheirOwnGroup() {
        TraceFixture fx = new TraceFixture(false, List.of());
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(call("c1", "write"), call("c2", "write")));
        assertEquals(Status.WAITING, tool(fx, "c2").status());
        assertNull(tool(fx, "c2").group());

        fx.feed(run, fx.finished(run, "c1", "ok", false));

        assertEquals(Status.RUNNING, tool(fx, "c2").status());
        assertNotEquals(tool(fx, "c1").group(), tool(fx, "c2").group());
    }

    @Test
    void blockedByAHookCarriesItsReasonAndTheHookRowLinksToIt() {
        TraceFixture fx = new TraceFixture(true, List.of(NO_RM));
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(call("c1", "rm"), call("c2", "read_file")));

        ToolCall blocked = tool(fx, "c1");
        assertEquals(Status.BLOCKED, blocked.status());
        assertEquals("destructive", blocked.reason());
        assertEquals("Tool call blocked: destructive", blocked.result());
        HookDecision stop = fx.records(HookDecision.class).getFirst();
        assertEquals(blocked.id(), stop.tool());
        assertEquals(Status.RUNNING, tool(fx, "c2").status());
    }

    @Test
    void invalidArgumentsAreBlockedWithTheirOwnCode() {
        TraceFixture fx = new TraceFixture(true, List.of());
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(badCall("c1", "read_file")));

        ToolCall blocked = tool(fx, "c1");
        assertEquals(Status.BLOCKED, blocked.status());
        assertEquals(TraceTools.INVALID_ARGUMENTS, blocked.code());
        assertEquals("{oops", blocked.rawArguments());
        assertEquals(blocked.head().at(), blocked.head().end());
    }

    @Test
    void aThrowingToolHookBlocksTheCallAndTheErrorRowKnowsItsPoint() {
        TraceFixture fx = new TraceFixture(true, List.of(THROWS));
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(call("c1", "read_file")));

        assertEquals("hook failed: throws", tool(fx, "c1").reason());
        List<HookDecision> hooks = fx.records(HookDecision.class);
        assertEquals(HookDecision.Result.ERROR, hooks.get(0).result());
        assertEquals(tantalum.value.hook.HookPoint.BEFORE_TOOL_CALL, hooks.get(0).point());
        assertEquals(HookDecision.Result.STOP, hooks.get(1).result());
    }

    @Test
    void cancelLeavesSentCallsUnknownAndUnsentOnesNotSent() {
        TraceFixture fx = new TraceFixture(false, List.of());
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(call("c1", "write"), call("c2", "write")));
        fx.feed(run, new Cancel(new RequestId("x")));

        ToolCall unknown = tool(fx, "c1");
        assertEquals(Status.UNKNOWN, unknown.status());
        assertEquals(ToolPairing.OUTCOME_UNKNOWN, unknown.code());
        assertEquals("run ended", unknown.reason());
        ToolCall notSent = tool(fx, "c2");
        assertEquals(Status.NOT_SENT, notSent.status());
        assertEquals(ToolPairing.ABORTED_BEFORE_DISPATCH, notSent.code());
        assertEquals("CANCELLED", notSent.reason());
    }

    @Test
    void restartInterruptionSaysSo() {
        TraceFixture fx = new TraceFixture(true, List.of());
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(call("c1", "read_file")));
        fx.feed(run, new RunInterrupted());

        assertEquals(Status.UNKNOWN, tool(fx, "c1").status());
        assertEquals("restart", tool(fx, "c1").reason());
    }

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
        "mcp__github__create_issue, github",
        "mcp__my_server__x__y, my_server",
        "mcp__nohash, -",
        "read_file, -",
        "mcp____x, -"
    })
    void mcpServerIsTheSegmentAfterThePrefix(String name, String server) {
        assertEquals(server, TraceTools.server(name));
    }
}
