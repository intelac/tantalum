package tantalum.kernel.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static tantalum.kernel.LoopFixture.call;
import static tantalum.kernel.LoopFixture.text;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.decision.SessionChange;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Params;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.json.Json;
import tantalum.value.model.StopReason;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionContext;

/**
 * plugin-settings 任务 17、18（P9，需求 5.2、5.3、5.6）：钩子插件的参数在组装上下文时记进会话，
 * 每个钩子只拿到自己插件的那一包，整个运行用同一份。
 */
class PluginParamsTest {

    /** 记下每次被调用时看到的参数。 */
    static final class Seen implements KernelHook {
        private final String plugin;
        final List<Params> params = new ArrayList<>();

        Seen(String plugin) {
            this.plugin = plugin;
        }

        @Override public String name() { return plugin + "Hook"; }
        @Override public String plugin() { return plugin; }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
            params.add(context.params());
            return Allow.PLAIN;
        }
        @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
            params.add(context.params());
            return Allow.PLAIN;
        }
    }

    private static Json.Obj obj(Object... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put((String) pairs[i], pairs[i + 1] instanceof Json json ? json : new Json.Num(BigDecimal.valueOf((Integer) pairs[i + 1])));
        }
        return new Json.Obj(fields);
    }

    private static void assemble(LoopFixture loop, Json.Obj params) {
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", List.of(), null, List.of(), null,
                List.of("quota", "scope"), Map.of(), null, params));
    }

    private static void endTurn(LoopFixture loop) {
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), text("ok"), StopReason.END_TURN, "m", null, null, null, null));
    }

    @Test
    void eachHookSeesOnlyItsOwnPluginsParams() {
        Seen quota = new Seen("quota");
        Seen scope = new Seen("scope");
        LoopFixture loop = new LoopFixture(quota, scope);
        loop.prompt("q1");
        assemble(loop, obj("quota", obj("tokens", 500), "scope", obj("depth", 2)));
        assertEquals(List.of(new Params(obj("tokens", 500))), quota.params);
        assertEquals(List.of(new Params(obj("depth", 2))), scope.params);
        assertFalse(quota.params.getFirst().has("depth"), "another plugin's params are out of reach");
    }

    /** 没记过参数的旧会话、没声明参数的插件：拿到 NONE，钩子用自己构造时的值。 */
    @Test
    void withoutRecordedParamsAHookGetsNone() {
        Seen quota = new Seen("quota");
        Seen scope = new Seen("scope");
        LoopFixture loop = new LoopFixture(quota, scope);
        loop.prompt("q1");
        assemble(loop, null);
        assertSame(Params.NONE, quota.params.getFirst());
        assertNull(loop.session().context().pluginParams());
        endTurn(loop);

        loop.prompt("q2");
        assemble(loop, obj("quota", obj("tokens", 500)));
        assertEquals(new Params(obj("tokens", 500)), quota.params.getLast());
        assertSame(Params.NONE, scope.params.getLast(), "a plugin without params in the record gets none");
    }

    /** 参数只在组装上下文时换：这次运行从头到尾用开始时那一份，下一次提问才用新的。 */
    @Test
    void theParamsHoldForTheWholeRunAndChangeWithTheNextPrompt() {
        Seen quota = new Seen("quota");
        LoopFixture loop = new LoopFixture(quota);
        loop.prompt("q1");
        assemble(loop, obj("quota", obj("tokens", 500)));
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), List.of(call("c1", "grep")), StopReason.TOOL_USE, "m", null,
                null, null, null));
        loop.feed(loop.runId(), new ToolFinished(loop.onlyCommandId(), "c1", "found", false, null));
        endTurn(loop);
        assertEquals(3, quota.params.size());
        quota.params.forEach(seen -> assertEquals(500, seen.number("tokens", 0)));

        SessionContext before = loop.session().context();
        loop.prompt("q2");
        assemble(loop, obj("quota", obj("tokens", 9)));
        assertEquals(9, quota.params.getLast().number("tokens", 0));
        SessionContext after = loop.session().context();
        // 只换了这一个字段
        assertEquals(before.withPluginParams(obj("quota", obj("tokens", 9))), after);
    }

    /** 需求 5.6：参数没变，这次组装不产生上下文的变化，结果行里就不会多写。 */
    @Test
    void unchangedParamsLeaveTheContextUntouched() {
        LoopFixture loop = new LoopFixture(new Seen("quota"));
        loop.prompt("q1");
        assemble(loop, obj("quota", obj("tokens", 500)));
        endTurn(loop);
        SessionContext before = loop.session().context();
        assertNotNull(before.pluginParams());

        loop.prompt("q2");
        KernelState ahead = loop.state;
        assemble(loop, obj("quota", obj("tokens", 500)));
        assertSame(before, loop.session().context(), "the same params do not replace the context");
        assertNull(SessionChange.between(ahead.sessions().get(LoopFixture.SESSION), loop.session()).context());
    }

    /** 参数没了（插件关了、不再声明）：会话里也清掉。 */
    @Test
    void paramsThatAreGoneAreCleared() {
        Seen quota = new Seen("quota");
        LoopFixture loop = new LoopFixture(quota);
        loop.prompt("q1");
        assemble(loop, obj("quota", obj("tokens", 500)));
        endTurn(loop);
        loop.prompt("q2");
        assemble(loop, null);
        assertNull(loop.session().context().pluginParams());
        assertSame(Params.NONE, quota.params.getLast());
    }

    @Test
    void everyWithKeepsTheParams() {
        Params mine = new Params(obj("tokens", 500));
        RunRef run = new RunRef(LoopFixture.USER, LoopFixture.SESSION, new RunId("s_0"));
        HookContext context = HookContext.of(run, Instant.EPOCH);
        assertSame(Params.NONE, context.params());
        context = context.withParams(mine);
        assertEquals(mine, context.withCall(call("c1", "grep")).params());
        assertEquals(mine, context.withFailure((ModelFailed) null).params());
        assertEquals(mine, context.withWork(null).params());
        assertEquals(mine, context.withCompaction(null, null).params());
        assertSame(Params.NONE, context.withParams(null).params());

        SessionContext session = new SessionContext("s", List.of(), null, List.of()).withPluginParams(obj("quota", obj("tokens", 500)));
        assertEquals(mine, session.withSections(List.of()).paramsOf("quota"));
        assertEquals(mine, session.withCompaction(null).paramsOf("quota"));
        assertEquals(mine, session.withActive(null, null).paramsOf("quota"));
        assertEquals(mine, session.withHooks(null).paramsOf("quota"));
        assertSame(Params.NONE, session.paramsOf("scope"));
    }
}
