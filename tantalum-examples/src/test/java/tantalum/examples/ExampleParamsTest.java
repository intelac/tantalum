package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.plugin.Param;
import tantalum.shell.plugin.Secrets;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.Workspace;
import tantalum.value.hook.Allow;
import tantalum.value.hook.Ask;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.Params;
import tantalum.value.hook.Stop;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.KernelState;
import tantalum.value.state.PrincipalState;

/**
 * plugin-settings 需求 9：四个示例插件的设置是插件页上的参数。构造时给的值（服务器配置）是自带默认；
 * 上下文里有参数就用参数，没有（旧会话继续跑、回放）就用构造时的值，行为和加参数之前一样。
 */
class ExampleParamsTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
    private static final HookContext OLD = HookContext.of(new RunRef(USER, SESSION, new RunId("r")), NOW);

    private static Json num(long value) {
        return new Json.Num(BigDecimal.valueOf(value));
    }

    private static Json list(String... names) {
        return new Json.Arr(List.of(names).stream().<Json>map(Json.Str::new).toList());
    }

    private static Params params(String key, Json value) {
        return new Params(new Json.Obj(Map.of(key, value)));
    }

    private static KernelState used(long tokens) {
        return KernelState.empty().with(PrincipalState.start(USER).add(LocalDate.of(2026, 10, 31), new TokenUsage(tokens, 0, 0, 0)));
    }

    private static HookContext call(HookContext context, String tool) {
        return context.withCall(new ToolCallBlock("c", tool, "{}", new Json.Null()));
    }

    @Test
    void theSettingsAreDeclaredWithTheServerConfigAsDefault() {
        Param tokens = new DailyQuotaHook(5000).params().get(0);
        assertEquals("tokens", tokens.key());
        assertEquals(Param.Owner.ADMIN, tokens.owner(), "only an admin sets the quota");
        assertEquals(num(5000), tokens.byDefault());
        assertEquals(1L, tokens.min());

        Param maxChars = new TruncateOutputHook(20000).params().get(0);
        assertEquals("maxChars", maxChars.key());
        assertEquals(Param.Owner.USER, maxChars.owner());
        assertEquals(num(20000), maxChars.byDefault());
        assertEquals(100L, maxChars.min());
        assertEquals(1_000_000L, maxChars.max());

        // 清单的默认值排过序：它会记进会话，同一份配置每次启动要得到同一个值
        Param readOnly = new ReadOnlyHook(Set.of("read_file", "grep", "ls")).params().get(0);
        assertEquals(Param.Type.LIST, readOnly.type());
        assertEquals(list("grep", "ls", "read_file"), readOnly.byDefault());
        Param approval = new ToolApprovalHook(Set.of("write_file", "bash")).params().get(0);
        assertEquals(Param.Owner.USER, approval.owner());
        assertEquals(list("bash", "write_file"), approval.byDefault());
    }

    /** qagent-align 需求 3.4：额度拿折算后的今日用量比——贵的模型用得少也可能先到，便宜的用得多也可能没到。 */
    @Test
    void theQuotaCountsTheWeighedAmount() {
        DailyQuotaHook quota = new DailyQuotaHook(500);
        LocalDate day = LocalDate.of(2026, 10, 31);
        KernelState heavy = KernelState.empty().with(PrincipalState.start(USER).add(day, new TokenUsage(100, 0, 0, 0, 600L)));
        assertInstanceOf(Stop.class, quota.beforeModelCall(heavy, OLD), "100 raw tokens weigh 600: over the 500 limit");
        KernelState light = KernelState.empty().with(PrincipalState.start(USER).add(day, new TokenUsage(900, 0, 0, 0, 90L)));
        assertSame(Allow.PLAIN, quota.beforeModelCall(light, OLD), "900 raw tokens weigh 90: under the limit");
        assertInstanceOf(Stop.class, quota.beforeModelCall(used(500), OLD), "not weighed: the sum of the four, as before");
    }

    /** 服务器配置写在页面范围之外（原来就能这么配）：照样能启动，范围跟着放宽。 */
    @Test
    void aConfiguredLimitOutsideThePageRangeStillStarts() {
        assertEquals(10L, new TruncateOutputHook(10).params().get(0).min());
        assertEquals(5_000_000L, new TruncateOutputHook(5_000_000).params().get(0).max());
    }

    @Test
    void theQuotaReadsItsLimitFromTheParams() {
        DailyQuotaHook hook = new DailyQuotaHook(100);
        assertSame(Allow.PLAIN, hook.beforeModelCall(used(60), OLD), "no params: the constructor value");
        assertInstanceOf(Stop.class, hook.beforeModelCall(used(100), OLD));
        assertInstanceOf(Stop.class, hook.beforeModelCall(used(60), OLD.withParams(params("tokens", num(50)))));
        assertSame(Allow.PLAIN, hook.beforeModelCall(used(100), OLD.withParams(params("tokens", num(500)))));
    }

    /** input-area 需求 6.8：页面上显示的上限就是拦截用的那个参数。 */
    @Test
    void theQuotaReportsTheSameLimitItEnforces() {
        DailyQuotaHook hook = new DailyQuotaHook(100);
        assertEquals(java.util.OptionalLong.of(100), hook.dailyTokens(tantalum.value.hook.Params.NONE));
        assertEquals(java.util.OptionalLong.of(50), hook.dailyTokens(OLD.withParams(params("tokens", num(50))).params()));
    }

    @Test
    void truncationReadsItsLimitAtCallTime(@TempDir Path dir) throws Exception {
        TruncateOutputHook hook = new TruncateOutputHook(4);
        ToolContext none = new ToolContext(USER, SESSION, new Workspace(dir));
        ToolOutput output = ToolOutput.ok("abcdefgh");
        assertEquals("ab\n[... 4 characters omitted ...]\ngh", hook.afterToolExecution(none, "t", new Json.Null(), output).text());
        ToolContext two = none.with(params("maxChars", num(2)), Secrets.NONE);
        assertEquals("a\n[... 6 characters omitted ...]\nh", hook.afterToolExecution(two, "t", new Json.Null(), output).text());
        ToolContext wide = none.with(params("maxChars", num(100)), Secrets.NONE);
        assertSame(output, hook.afterToolExecution(wide, "t", new Json.Null(), output));
    }

    @Test
    void readOnlyReadsItsListFromTheParams() {
        ReadOnlyHook hook = new ReadOnlyHook(Set.of("grep"));
        assertSame(Allow.PLAIN, hook.beforeToolCall(KernelState.empty(), call(OLD, "grep")));
        assertInstanceOf(Stop.class, hook.beforeToolCall(KernelState.empty(), call(OLD, "ls")));
        HookContext mine = OLD.withParams(params("tools", list("ls")));
        assertSame(Allow.PLAIN, hook.beforeToolCall(KernelState.empty(), call(mine, "ls")));
        assertInstanceOf(Stop.class, hook.beforeToolCall(KernelState.empty(), call(mine, "grep")), "the user's list replaces the default");
        // 清单清空：什么都不放行
        assertInstanceOf(Stop.class, hook.beforeToolCall(KernelState.empty(), call(OLD.withParams(params("tools", list())), "grep")));
    }

    @Test
    void approvalReadsItsListFromTheParams() {
        ToolApprovalHook hook = new ToolApprovalHook(Set.of("write_file"));
        assertInstanceOf(Ask.class, hook.beforeToolCall(KernelState.empty(), call(OLD, "write_file")));
        assertSame(Allow.PLAIN, hook.beforeToolCall(KernelState.empty(), call(OLD, "bash")));
        HookContext mine = OLD.withParams(params("tools", list("bash")));
        assertInstanceOf(Ask.class, hook.beforeToolCall(KernelState.empty(), call(mine, "bash")));
        assertSame(Allow.PLAIN, hook.beforeToolCall(KernelState.empty(), call(mine, "write_file")));
    }
}
