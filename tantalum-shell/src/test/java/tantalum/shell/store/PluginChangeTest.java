package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.kernel.Limits;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.loop.SessionSlice;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.RunTool;
import tantalum.value.decision.Decision;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.UserPrompt;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;

/**
 * record-results 需求 3.3、11.2：钩子的规则改了，旧对话不变。恢复只读结果行，不经过钩子；用新规则重放内核（以前的做法）
 * 会改写历史。新规则只作用于之后的新步骤。
 */
class PluginChangeTest {

    @TempDir
    Path dir;

    private ReplayScenario scenario;

    /** 访问范围：只放行名单里的工具。 */
    private static KernelHook scope(Set<String> allowed) {
        return new KernelHook() {
            @Override public String name() { return "scope"; }
            @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
                return allowed.contains(context.call().name()) ? Allow.PLAIN : new Stop("outside the allowed scope");
            }
        };
    }

    private static Kernel kernel(KernelHook hook) {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        return new Kernel(table, new HookChain(List.of(ReplayScenario.QUOTA_HOOK, hook)), (tool, args) -> tool.startsWith("safe"),
                Limits.DEFAULT);
    }

    @BeforeEach
    void start() throws Exception {
        scenario = new ReplayScenario(dir, List.of(ReplayScenario.QUOTA_HOOK, scope(Set.of("safe_read"))));
        scenario.run();
        scenario.writer.flush();
    }

    @AfterEach
    void stop() throws Exception {
        scenario.close();
    }

    @Test
    void restoringIgnoresTheNewRuleWhileReplayingTheKernelWouldRewriteHistory() throws Exception {
        SessionFileReader reader = new SessionFileReader(EventCodec.withBuiltins());
        UsageLedger usage = new UsageLedger(scenario.index, scenario.writer);
        Kernel narrower = kernel(scope(Set.of()));

        SessionSlice restored = new RecordReplay(scenario.paths, reader).load(ReplayScenario.ALICE, ReplayScenario.TOOLS, Long.MAX_VALUE)
                .orElseThrow().slice();
        SessionSlice withOldRule = new SessionReplay(scenario.paths, reader, usage, scenario.kernel)
                .load(ReplayScenario.ALICE, ReplayScenario.TOOLS, Long.MAX_VALUE).orElseThrow();
        SessionSlice withNewRule = new SessionReplay(scenario.paths, reader, usage, narrower)
                .load(ReplayScenario.ALICE, ReplayScenario.TOOLS, Long.MAX_VALUE).orElseThrow();

        assertTrue(restored.session().messages().toString().contains("result of t1"), "the tools really ran back then");
        assertEquals(withOldRule.session(), restored.session());
        assertEquals(withOldRule.transcript(), restored.transcript());
        assertNotEquals(withNewRule.session(), restored.session(), "replaying with the new rule would change what happened");
    }

    @Test
    void theNewRuleAppliesToTheNextStepOnly() throws Exception {
        SessionFileReader reader = new SessionFileReader(EventCodec.withBuiltins());
        SessionSlice restored = new RecordReplay(scenario.paths, reader).load(ReplayScenario.ALICE, ReplayScenario.TOOLS, Long.MAX_VALUE)
                .orElseThrow().slice();
        KernelState state = KernelState.empty().withResident(restored.session(), restored.runs());

        assertTrue(nextToolCall(kernel(scope(Set.of("safe_read"))), state, restored.seq()).commands().stream().anyMatch(RunTool.class::isInstance));
        assertTrue(nextToolCall(kernel(scope(Set.of())), state, restored.seq()).commands().stream().noneMatch(RunTool.class::isInstance),
                "the narrower rule blocks the new call");
    }

    /** 接着旧对话再问一次，模型要调一个工具：返回内核对这次调用的决定。组装上下文、自动压缩的摘要调用照常回答。 */
    private static Decision nextToolCall(Kernel kernel, KernelState state, long lastSeq) {
        long seq = lastSeq + 1;
        Decision decision = kernel.step(state, envelope(seq++, null, new UserPrompt(new SubmissionId("sub-next"),
                new RequestId("req-next"), new OwnerRef.OfSession(ReplayScenario.TOOLS), "one more file")));
        while (true) {
            var command = decision.commands().getFirst();
            var run = command.run().run();
            switch (command) {
                case AssembleContext assemble -> decision = kernel.step(decision.newState(), envelope(seq++, run,
                        new ContextAssembled(assemble.id(), "system", List.of(), ReplayScenario.SETTINGS, List.of())));
                case CallModel call when !call.prompt().output().toolsAllowed() -> decision = kernel.step(decision.newState(),
                        envelope(seq++, run, new ModelReplied(call.id(), List.of(new TextBlock("the summary of everything so far")),
                                StopReason.END_TURN, "m", null, null, null, null)));
                case CallModel call -> {
                    ToolCallBlock tool = new ToolCallBlock("t9", "safe_read", "{\"p\":9}", new Json.Obj(Map.of("p", new Json.Num(BigDecimal.TEN))));
                    return kernel.step(decision.newState(), envelope(seq, run,
                            new ModelReplied(call.id(), List.of(tool), StopReason.TOOL_USE, "m", null, null, null, null)));
                }
                default -> throw new AssertionError("unexpected " + command);
            }
        }
    }

    private static EventEnvelope envelope(long seq, tantalum.value.id.RunId run, tantalum.value.event.Event event) {
        return new EventEnvelope(seq, ReplayScenario.AT, ReplayScenario.ALICE, ReplayScenario.TOOLS, run, event);
    }
}
