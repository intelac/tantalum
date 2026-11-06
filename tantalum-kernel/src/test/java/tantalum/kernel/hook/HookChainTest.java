package tantalum.kernel.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.Replay;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.DoWork;
import tantalum.value.command.RunTool;
import tantalum.value.decision.Decision;
import tantalum.value.decision.Fact;
import tantalum.value.decision.HookDecided;
import tantalum.value.decision.HookReported;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.PromptPatch;
import tantalum.value.hook.Stop;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.StopReason;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.KernelState;

class HookChainTest {

    private static final RunRef RUN = new RunRef(new Principal("p"), new SessionId("s"), new RunId("r"));
    private static final HookContext CONTEXT = HookContext.of(RUN, Instant.EPOCH);
    private static final ToolSpec CITE = new ToolSpec("cite", "", new Json.Null());
    private static final ToolSpec LOOKUP = new ToolSpec("lookup", "", new Json.Null());

    private final List<String> calls = new ArrayList<>();

    private KernelHook hook(String name, boolean mandatory, HookResult result) {
        return new KernelHook() {
            @Override public String name() { return name; }
            @Override public boolean mandatory() { return mandatory; }
            @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
                calls.add(name);
                return result;
            }
            @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
                calls.add(name);
                return result;
            }
        };
    }

    private KernelHook throwing(String name, boolean mandatory) {
        return new KernelHook() {
            @Override public String name() { return name; }
            @Override public boolean mandatory() { return mandatory; }
            @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
                throw new IllegalStateException("boom");
            }
            @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
                throw new IllegalStateException("boom");
            }
        };
    }

    private HookOutcome modelCall(KernelHook... hooks) {
        return new HookChain(List.of(hooks)).run(HookPoint.BEFORE_MODEL_CALL, KernelState.empty(), CONTEXT);
    }

    @Test
    void hooksRunInRegistrationOrder() {
        HookOutcome outcome = modelCall(hook("a", false, Allow.PLAIN), hook("b", false, Allow.PLAIN));
        assertEquals(List.of("a", "b"), calls);
        assertInstanceOf(Allow.class, outcome.result());
    }

    @Test
    void stopSkipsLaterHooks() {
        HookOutcome outcome = modelCall(hook("a", false, new Stop("quota")), hook("b", false, Allow.PLAIN));
        assertEquals(List.of("a"), calls);
        assertEquals(new Stop("quota"), outcome.result());
    }

    @Test
    void needWorkSkipsLaterHooks() {
        NeedWork work = new NeedWork("citation", new Json.Null());
        HookOutcome outcome = modelCall(hook("a", false, work), hook("b", false, Allow.PLAIN));
        assertEquals(List.of("a"), calls);
        assertEquals(work, outcome.result());
    }

    @Test
    void allowPatchesAreMergedInOrder() {
        HookOutcome outcome = modelCall(
                hook("a", false, new Allow(new PromptPatch("one", List.of()))),
                hook("b", false, new Allow(new PromptPatch("two", List.of()))));
        assertEquals(new Allow(new PromptPatch("onetwo", List.of())), outcome.result());
    }

    @Test
    void mandatoryExceptionStopsAndIsReported() {
        HookOutcome outcome = modelCall(throwing("quota", true), hook("b", false, Allow.PLAIN));
        assertInstanceOf(Stop.class, outcome.result());
        assertEquals(List.of(), calls);
        HookReported reported = assertInstanceOf(HookReported.class, outcome.facts().getFirst());
        assertEquals("quota", reported.hook());
    }

    @Test
    void optionalExceptionIsSkippedAndReported() {
        HookOutcome outcome = modelCall(throwing("metrics", false), hook("b", false, Allow.PLAIN));
        assertInstanceOf(Allow.class, outcome.result());
        assertEquals(List.of("b"), calls);
        assertEquals(1, outcome.facts().size());
    }

    /**
     * A2：默认关、用户能关的策略插件（failClosed）和管理员设成必须开启的插件（tantalum.hooks.required），出错都按拦截处理，
     * 和声明了 mandatory 的一样；否则审批钩子出一次错就等于放行。
     */
    @Test
    void failClosedAndAdminRequiredHooksStopOnException() {
        KernelHook approval = new KernelHook() {
            @Override public String name() { return "approval"; }
            @Override public boolean failClosed() { return true; }
            @Override public HookResult beforeModelCall(KernelState state, HookContext context) { throw new IllegalStateException("boom"); }
        };
        assertInstanceOf(Stop.class, modelCall(approval, hook("b", false, Allow.PLAIN)).result());
        HookOutcome required = new HookChain(List.of(throwing("metrics", false), hook("b", false, Allow.PLAIN)), java.util.Set.of("metrics"))
                .run(HookPoint.BEFORE_MODEL_CALL, KernelState.empty(), CONTEXT);
        assertInstanceOf(Stop.class, required.result());
        assertEquals(List.of(), calls);
    }

    /** 返回 null 违反契约：不能当作放行，也不能让后面的钩子被跳过而无人知道。 */
    @Test
    void nullResultIsTreatedAsStopAndReported() {
        HookOutcome outcome = modelCall(hook("broken", false, null), hook("b", false, Allow.PLAIN));
        Stop stop = assertInstanceOf(Stop.class, outcome.result());
        assertTrue(stop.reason().contains("broken"));
        assertEquals(List.of("broken"), calls);
        assertEquals("broken", assertInstanceOf(HookReported.class, outcome.facts().getFirst()).hook());
    }

    @Test
    void beforeToolCallExceptionAlwaysBlocks() {
        HookOutcome outcome = new HookChain(List.of(throwing("metrics", false)))
                .run(HookPoint.BEFORE_TOOL_CALL, KernelState.empty(), CONTEXT);
        assertInstanceOf(Stop.class, outcome.result());
        assertTrue(!outcome.facts().isEmpty());
    }

    /** 超过工作轮数上限时，原因里要写是哪个钩子：取拦截或要求工作的那个，异常和 null 算在出问题的钩子上。 */
    @Test
    void decidedByNamesTheHookThatDecided() {
        assertNull(modelCall(hook("a", false, Allow.PLAIN), hook("b", false, Allow.PLAIN)).decidedBy());
        assertEquals("b", modelCall(hook("a", false, Allow.PLAIN), hook("b", false, new Stop("quota"))).decidedBy());
        assertEquals("a", modelCall(hook("a", false, new NeedWork("citation", new Json.Null())), hook("b", false, Allow.PLAIN)).decidedBy());
        assertEquals("quota", modelCall(throwing("quota", true), hook("b", false, Allow.PLAIN)).decidedBy());
        assertNull(modelCall(throwing("metrics", false), hook("b", false, Allow.PLAIN)).decidedBy(), "a skipped hook decides nothing");
        assertEquals("broken", modelCall(hook("a", false, Allow.PLAIN), hook("broken", false, null)).decidedBy());
        assertEquals("metrics", new HookChain(List.of(hook("a", false, Allow.PLAIN), throwing("metrics", false)))
                .run(HookPoint.BEFORE_TOOL_CALL, KernelState.empty(), CONTEXT).decidedBy());
        assertNull(new HookChain(List.of()).run(HookPoint.BEFORE_RUN_END, KernelState.empty(), CONTEXT).decidedBy());
    }

    // ---- HookDecided：每个钩子的决定报一条，只放行、没有补丁时不报 ----

    /** 六个位置都返回同一个结果的钩子。 */
    private static KernelHook everywhere(String name, boolean mandatory, HookResult result) {
        return new KernelHook() {
            @Override public String name() { return name; }
            @Override public boolean mandatory() { return mandatory; }
            @Override public HookResult beforeModelCall(KernelState state, HookContext context) { return result; }
            @Override public HookResult beforeToolCall(KernelState state, HookContext context) { return result; }
            @Override public HookResult onFailure(KernelState state, HookContext context) { return result; }
            @Override public HookResult beforeRunEnd(KernelState state, HookContext context) { return result; }
            @Override public HookResult onWorkResult(KernelState state, HookContext context) { return result; }
            @Override public HookResult beforeCompact(KernelState state, HookContext context) { return result; }
        };
    }

    /** 上下文里总带一个工具调用：只有工具调用前才该把它的 ID 写进事实。 */
    private static List<Fact> facts(HookPoint point, KernelHook... hooks) {
        HookContext context = CONTEXT.withCall(new ToolCallBlock("call-1", "read_file", "{}", new Json.Obj(java.util.Map.of())));
        return new HookChain(List.of(hooks)).run(point, KernelState.empty(), context).facts();
    }

    private static String callAt(HookPoint point) {
        return point == HookPoint.BEFORE_TOOL_CALL ? "call-1" : null;
    }

    static Stream<Arguments> everyPointAndResult() {
        return Stream.of(HookPoint.values()).flatMap(point -> Stream.of(
                Arguments.of(point, new Stop("quota"),
                        new HookDecided(RUN, "h", point, HookDecided.Kind.STOP, "quota", 0, List.of(), callAt(point), null)),
                Arguments.of(point, new NeedWork("citation", new Json.Null()),
                        new HookDecided(RUN, "h", point, HookDecided.Kind.NEED_WORK, null, 0, List.of(), callAt(point), "citation")),
                Arguments.of(point, new Allow(new PromptPatch("cite sources", List.of(CITE, LOOKUP))),
                        new HookDecided(RUN, "h", point, HookDecided.Kind.PATCH, null, 12, List.of("cite", "lookup"), callAt(point), null))));
    }

    @ParameterizedTest
    @MethodSource("everyPointAndResult")
    void eachDecisionIsReportedOnceAtEveryPoint(HookPoint point, HookResult result, HookDecided expected) {
        assertEquals(List.of(expected), facts(point, everywhere("h", false, result)));
    }

    /** 只有工具补丁、只有 system 补丁也算补丁。 */
    @ParameterizedTest
    @EnumSource(HookPoint.class)
    void halfAPatchIsStillAPatch(HookPoint point) {
        assertEquals(List.of(new HookDecided(RUN, "tools", point, HookDecided.Kind.PATCH, null, 0, List.of("cite"), callAt(point), null),
                        new HookDecided(RUN, "system", point, HookDecided.Kind.PATCH, null, 3, List.of(), callAt(point), null)),
                facts(point, everywhere("tools", false, new Allow(new PromptPatch("", List.of(CITE)))),
                        everywhere("system", false, new Allow(new PromptPatch("abc", List.of())))));
    }

    @ParameterizedTest
    @EnumSource(HookPoint.class)
    void allowWithoutAPatchIsNotReported(HookPoint point) {
        assertEquals(List.of(), facts(point, everywhere("plain", false, Allow.PLAIN),
                everywhere("empty", false, new Allow(new PromptPatch("", List.of())))));
    }

    /** 每个钩子报自己的补丁（不是合并后的），按注册顺序；拦截之后的钩子不再运行，也不报。 */
    @Test
    void severalHooksReportInOrderWithTheirOwnPatch() {
        List<Fact> facts = facts(HookPoint.BEFORE_MODEL_CALL,
                everywhere("a", false, new Allow(new PromptPatch("one", List.of(CITE)))),
                everywhere("b", false, Allow.PLAIN),
                everywhere("c", false, new Allow(new PromptPatch("three!", List.of(LOOKUP, CITE)))),
                everywhere("d", false, new NeedWork("check", new Json.Null())),
                everywhere("e", false, new Stop("never asked")));
        assertEquals(List.of(
                new HookDecided(RUN, "a", HookPoint.BEFORE_MODEL_CALL, HookDecided.Kind.PATCH, null, 3, List.of("cite"), null, null),
                new HookDecided(RUN, "c", HookPoint.BEFORE_MODEL_CALL, HookDecided.Kind.PATCH, null, 6, List.of("lookup", "cite"), null, null),
                new HookDecided(RUN, "d", HookPoint.BEFORE_MODEL_CALL, HookDecided.Kind.NEED_WORK, null, 0, List.of(), null, "check")), facts);
    }

    /** 钩子出错让链拦截时，HookReported 后紧跟一条 STOP，理由是链用的那句；只是跳过时不报 STOP。 */
    @Test
    void aFailureThatStopsTheChainIsFollowedByStop() {
        List<Fact> mandatory = facts(HookPoint.BEFORE_MODEL_CALL, throwing("quota", true), hook("b", false, Allow.PLAIN));
        assertEquals(2, mandatory.size());
        assertEquals("quota", assertInstanceOf(HookReported.class, mandatory.get(0)).hook());
        assertEquals(new HookDecided(RUN, "quota", HookPoint.BEFORE_MODEL_CALL, HookDecided.Kind.STOP, "hook failed: quota",
                0, List.of(), null, null), mandatory.get(1));

        List<Fact> tool = facts(HookPoint.BEFORE_TOOL_CALL, throwing("metrics", false));
        assertInstanceOf(HookReported.class, tool.get(0));
        assertEquals(new HookDecided(RUN, "metrics", HookPoint.BEFORE_TOOL_CALL, HookDecided.Kind.STOP, "hook failed: metrics",
                0, List.of(), "call-1", null), tool.get(1));

        List<Fact> broken = facts(HookPoint.BEFORE_RUN_END, everywhere("broken", false, null), everywhere("b", false, new Stop("x")));
        assertInstanceOf(HookReported.class, broken.get(0));
        assertEquals(new HookDecided(RUN, "broken", HookPoint.BEFORE_RUN_END, HookDecided.Kind.STOP, "hook returned null: broken",
                0, List.of(), null, null), broken.get(1));
        assertEquals(2, broken.size());

        List<Fact> skipped = facts(HookPoint.BEFORE_MODEL_CALL, throwing("metrics", false), hook("b", false, Allow.PLAIN));
        assertEquals(1, skipped.size());
        assertInstanceOf(HookReported.class, skipped.getFirst());
    }

    /**
     * 事实不进 journal，回放时重新算出：实时得到的和回放得到的必须相同。
     * 同时钉住轨迹要用的不变式：每个发出 DoWork 的决定里恰好有一条不在压缩前的 NEED_WORK，它就是要这次工作的钩子。
     */
    @Test
    void replayReportsTheSameDecisions() {
        KernelHook patcher = new KernelHook() {
            @Override public String name() { return "patcher"; }
            @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
                return new Allow(new PromptPatch("be brief", List.of(CITE)));
            }
        };
        KernelHook blocker = new KernelHook() {
            @Override public String name() { return "blocker"; }
            @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
                return context.call().name().equals("danger") ? new Stop("not allowed") : Allow.PLAIN;
            }
        };
        KernelHook checker = new KernelHook() {
            @Override public String name() { return "checker"; }
            @Override public HookResult beforeRunEnd(KernelState state, HookContext context) {
                return context.work() == null ? new NeedWork("check", new Json.Null()) : Allow.PLAIN;
            }
        };
        LoopFixture loop = new LoopFixture(patcher, blocker, checker);
        List<Fact> live = new ArrayList<>();
        live.addAll(loop.prompt("hi").facts());
        live.addAll(loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", List.of(), null, List.of())).facts());
        int modelCalls = 0;
        for (int step = 0; step < 20 && !loop.run().isEnded(); step++) {
            Command command = loop.last.commands().getFirst();
            Decision decision = switch (command) {
                case CallModel model -> modelCalls++ == 0
                        ? loop.feed(loop.runId(), new ModelReplied(model.id(), List.of(LoopFixture.call("t1", "safe_read"),
                                LoopFixture.call("t2", "danger")), StopReason.TOOL_USE, "m", null, null, null, null))
                        : loop.feed(loop.runId(), new ModelReplied(model.id(), LoopFixture.text("done"), StopReason.END_TURN, "m", null, null, null, null));
                case RunTool tool -> loop.feed(loop.runId(), new ToolFinished(tool.id(), tool.call().id(), "ok", false, null));
                case DoWork work -> loop.feed(loop.runId(), new WorkDone(work.id(), work.workType(), new Json.Null(), false, null, null));
                default -> throw new AssertionError("unexpected command " + command);
            };
            live.addAll(decision.facts());
            if (decision.commands().stream().anyMatch(DoWork.class::isInstance)) {
                assertEquals(1, decision.facts().stream().filter(fact -> fact instanceof HookDecided hook
                        && hook.kind() == HookDecided.Kind.NEED_WORK && hook.point() != HookPoint.BEFORE_COMPACT).count(), decision.facts().toString());
            }
        }
        assertTrue(loop.run().isEnded(), loop.phase());

        List<HookDecided> decided = live.stream().filter(HookDecided.class::isInstance).map(HookDecided.class::cast).toList();
        assertEquals(List.of("patcher:PATCH", "blocker:STOP", "patcher:PATCH", "checker:NEED_WORK"),
                decided.stream().map(fact -> fact.hook() + ":" + fact.kind()).toList());
        assertEquals("t2", decided.get(1).toolCall());
        assertEquals(live, Replay.run(loop.kernel, loop.journal).facts());
    }
}
