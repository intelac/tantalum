package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tantalum.examples.HttpStub.Reply;
import tantalum.shell.handler.WorkHandler;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.Workspace;
import tantalum.shell.tool.Workspaces;
import tantalum.value.command.DoWork;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.FailureReason;
import tantalum.value.state.KernelState;

class ProviderBackoffHookTest {

    private static final Principal USER = new Principal("u");
    private static final RunRef RUN = new RunRef(USER, new SessionId("s"), new RunId("r"));
    private static final HookContext CONTEXT = HookContext.of(RUN, Instant.EPOCH);
    private static final ProviderBackoffHook HOOK = new ProviderBackoffHook(Duration.ofMinutes(1));
    private static final Duration POLL = Duration.ofMillis(20);

    @TempDir
    Path dir;

    private static ModelFailed failure(FailureReason reason, Duration retryAfter) {
        return new ModelFailed(new CommandId("c"), reason, true, retryAfter, null, reason + " from provider");
    }

    private static Object onFailure(ModelFailed failure) {
        return HOOK.onFailure(KernelState.empty(), CONTEXT.withFailure(failure));
    }

    @Test
    void rateLimitWaitLongerThanTheLimitStopsAndSaysHowLong() {
        Stop stop = assertInstanceOf(Stop.class, onFailure(failure(FailureReason.RATE_LIMITED, Duration.ofMinutes(10))));
        assertTrue(stop.reason().contains("600s"), stop.reason());
        assertTrue(stop.reason().contains("60s"), stop.reason());
    }

    @Test
    void rateLimitWaitWithinTheLimitIsLeftToTheKernel() {
        assertSame(Allow.PLAIN, onFailure(failure(FailureReason.RATE_LIMITED, Duration.ofMinutes(1))));
        assertSame(Allow.PLAIN, onFailure(failure(FailureReason.RATE_LIMITED, null)));
    }

    @ParameterizedTest
    @EnumSource(value = FailureReason.class, names = {"SERVER_ERROR", "CONNECTION_ERROR"})
    void providerTroubleAsksForAStatusCheck(FailureReason reason) {
        NeedWork need = assertInstanceOf(NeedWork.class, onFailure(failure(reason, null)));
        assertEquals(ProviderStatusWork.TYPE, need.workType());
        assertEquals(new Json.Obj(Map.of("reason", new Json.Str(reason.name()), "detail", new Json.Str(reason + " from provider"))),
                need.input());
        // 输入进 journal，键的顺序要固定。
        assertEquals(List.of("reason", "detail"), List.copyOf(((Json.Obj) need.input()).fields().keySet()));
    }

    @ParameterizedTest
    @EnumSource(value = FailureReason.class, names = {"REFUSAL", "TIMEOUT", "INVALID_REQUEST", "UNKNOWN"})
    void otherFailuresAreLeftToTheKernel(FailureReason reason) {
        assertSame(Allow.PLAIN, onFailure(failure(reason, null)));
    }

    /** 失败后要求工作会跳过内核内置的超长恢复，所以超长必须放行。 */
    @Test
    void contextOverflowIsAllowedSoTheBuiltInRecoveryRuns() {
        assertSame(Allow.PLAIN, onFailure(failure(FailureReason.CONTEXT_OVERFLOW, Duration.ofHours(1))));
    }

    @Test
    void otherHookPointsAreUntouched() {
        assertSame(Allow.PLAIN, HOOK.onFailure(KernelState.empty(), CONTEXT));
        assertSame(Allow.PLAIN, HOOK.beforeModelCall(KernelState.empty(), CONTEXT));
        assertEquals("providerBackoff", HOOK.name());
        assertFalse(HOOK.mandatory());
    }

    @Test
    void constructorsRejectBadDurations() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderBackoffHook(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new ProviderStatusWork(URI.create("http://127.0.0.1/"), Duration.ZERO));
    }

    @Test
    void statusWorkEndsOnceTheProviderIsHealthy() throws Exception {
        try (HttpStub stub = new HttpStub((n, body) -> new Reply(n < 3 ? 503 : 200, ""))) {
            WorkResult result = new ProviderStatusWork(stub.uri(), POLL).run(new ToolContext(USER, new Workspace(dir)), new Json.Null());
            assertEquals(new Json.Obj(Map.of("healthy", new Json.Bool(true), "checks", new Json.Num(BigDecimal.valueOf(3)))), result.result());
            assertEquals(null, result.continueText());
            assertEquals(3, stub.requests().size());
        }
    }

    /** 一直不恢复：工作自己不停，由 WorkHandler 的超时结束，结果是失败。 */
    @Test
    void statusWorkThatNeverRecoversEndsByTheWorkTimeout() throws Exception {
        try (HttpStub stub = new HttpStub((n, body) -> new Reply(503, ""))) {
            WorkDone done = runThroughHandler(new ProviderStatusWork(stub.uri(), POLL));
            assertTrue(done.failed());
            assertTrue(((Json.Str) done.result()).value().contains("timed out"), done.result().toString());
            assertTrue(stub.requests().size() > 1, "kept polling: " + stub.requests().size());
        }
    }

    @Test
    void unreachableStatusUrlKeepsPollingUntilTheWorkTimeout() throws Exception {
        WorkDone done = runThroughHandler(new ProviderStatusWork(HttpStub.closedPort(), POLL));
        assertTrue(done.failed());
        assertTrue(((Json.Str) done.result()).value().contains("timed out"), done.result().toString());
    }

    /** 超时和取消都靠中断：中断后循环要真的停下，不能在后台一直查。 */
    @Test
    void interruptStopsThePolling() throws Exception {
        try (HttpStub stub = new HttpStub((n, body) -> new Reply(503, ""))) {
            AtomicReference<Throwable> ended = new AtomicReference<>();
            ProviderStatusWork work = new ProviderStatusWork(stub.uri(), POLL);
            ToolContext context = new ToolContext(USER, new Workspace(dir));
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    work.run(context, new Json.Null());
                } catch (Throwable stopped) {
                    ended.set(stopped);
                }
            });
            while (stub.requests().isEmpty()) {
                Thread.onSpinWait();
            }
            worker.interrupt();
            worker.join(Duration.ofSeconds(5));
            assertFalse(worker.isAlive());
            assertInstanceOf(InterruptedException.class, ended.get());
        }
    }

    private WorkDone runThroughHandler(ProviderStatusWork work) throws Exception {
        WorkHandler handler = new WorkHandler(Workspaces.perUser(dir), List.of(work), Duration.ofMillis(300));
        return (WorkDone) handler.handle(new DoWork(new CommandId("w"), RUN, ProviderStatusWork.TYPE, new Json.Null()));
    }
}
