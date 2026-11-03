package tantalum.shell.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.WorkType;
import tantalum.value.command.DoWork;
import tantalum.value.event.WorkDone;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

class WorkHandlerTest {

    private static final java.time.Duration TIMEOUT = java.time.Duration.ofSeconds(5);
    private static final RunRef RUN = new RunRef(new Principal("p"), new SessionId("s"), new RunId("r"));

    private static DoWork work(String type) {
        return new DoWork(new CommandId("w"), RUN, type, new Json.Str("input"));
    }

    private static WorkType type(String name, WorkResult result) {
        return new WorkType() {
            @Override public String name() { return name; }
            @Override public WorkResult run(tantalum.shell.plugin.ToolContext context, Json input) { return result; }
        };
    }

    @Test
    void dispatchesByType() throws Exception {
        WorkHandler handler = new WorkHandler(tantalum.shell.tool.Workspaces.shared(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))), tantalum.shell.access.PluginGate.allOn(new tantalum.shell.tool.ToolCatalog(), List.of(), List.of(), List.of(type("a", new WorkResult(new Json.Str("done"), "continue")))), TIMEOUT, java.time.Clock.systemUTC());
        WorkDone done = assertInstanceOf(WorkDone.class, handler.handle(work("a")));
        assertEquals(new Json.Str("done"), done.result());
        assertEquals("continue", done.continueText());
        assertEquals("a", done.workType());
        assertFalse(done.failed());
    }

    @Test
    void unknownTypeFails() throws Exception {
        WorkDone done = assertInstanceOf(WorkDone.class, new WorkHandler(tantalum.shell.tool.Workspaces.shared(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))), tantalum.shell.access.PluginGate.allOn(new tantalum.shell.tool.ToolCatalog(), List.of(), List.of(), List.of()), TIMEOUT, java.time.Clock.systemUTC()).handle(work("nope")));
        assertTrue(done.failed());
    }

    @Test
    void failureBecomesAFailedResult() {
        WorkDone done = assertInstanceOf(WorkDone.class,
                new WorkHandler(tantalum.shell.tool.Workspaces.shared(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))), tantalum.shell.access.PluginGate.allOn(new tantalum.shell.tool.ToolCatalog(), List.of(), List.of(), List.of()), TIMEOUT, java.time.Clock.systemUTC()).failed(work("a"), new IllegalStateException("boom")));
        assertTrue(done.failed());
        assertEquals(new Json.Str("java.lang.IllegalStateException: boom"), done.result());
    }

    @Test
    void duplicateTypeFails() {
        WorkType a = type("a", new WorkResult(new Json.Null(), null));
        assertThrows(IllegalStateException.class, () -> new WorkHandler(tantalum.shell.tool.Workspaces.shared(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))), tantalum.shell.access.PluginGate.allOn(new tantalum.shell.tool.ToolCatalog(), List.of(), List.of(), List.of(a, a)), TIMEOUT, java.time.Clock.systemUTC()));
    }

    private static WorkHandler handler(WorkType... types) {
        return new WorkHandler(tantalum.shell.tool.Workspaces.shared(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))), tantalum.shell.access.PluginGate.allOn(new tantalum.shell.tool.ToolCatalog(), List.of(), List.of(), List.of(types)), java.time.Duration.ofMillis(200), java.time.Clock.systemUTC());
    }

    /** 阻塞的插件不能永久占住命令：超时变成失败结果，插件线程被中断。 */
    @Test
    void blockedWorkTimesOutAndIsInterrupted() throws Exception {
        java.util.concurrent.CountDownLatch interrupted = new java.util.concurrent.CountDownLatch(1);
        WorkType stuck = new WorkType() {
            @Override public String name() { return "stuck"; }
            @Override public WorkResult run(tantalum.shell.plugin.ToolContext context, Json input) {
                try {
                    Thread.sleep(java.time.Duration.ofMinutes(1));
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                }
                return new WorkResult(new Json.Null(), null);
            }
        };
        WorkDone done = assertInstanceOf(WorkDone.class, handler(stuck).handle(work("stuck")));
        assertTrue(done.failed());
        assertTrue(((Json.Str) done.result()).value().contains("timed out"));
        assertTrue(interrupted.await(5, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void throwingWorkBecomesAFailedResult() throws Exception {
        WorkType broken = new WorkType() {
            @Override public String name() { return "broken"; }
            @Override public WorkResult run(tantalum.shell.plugin.ToolContext context, Json input) {
                throw new StackOverflowError();
            }
        };
        WorkDone done = assertInstanceOf(WorkDone.class, handler(broken).handle(work("broken")));
        assertTrue(done.failed());
        assertTrue(((Json.Str) done.result()).value().contains("StackOverflowError"));
    }

    /** 开始时刻在执行前取：完成和失败都带；没有这种工作、处理器自己失败时没有真正执行，为空。 */
    @Test
    void startedAtIsTakenBeforeTheWorkRuns() throws Exception {
        java.time.Instant t0 = java.time.Instant.parse("2026-11-03T01:00:00Z");
        java.time.Instant t1 = t0.plusSeconds(2);
        ManualClock clock = new ManualClock(t0);
        WorkType slow = new WorkType() {
            @Override public String name() { return "slow"; }
            @Override public WorkResult run(tantalum.shell.plugin.ToolContext context, Json input) {
                clock.now = t1;
                return new WorkResult(new Json.Null(), null);
            }
        };
        WorkType broken = new WorkType() {
            @Override public String name() { return "broken"; }
            @Override public WorkResult run(tantalum.shell.plugin.ToolContext context, Json input) {
                throw new IllegalStateException("boom");
            }
        };
        WorkHandler handler = new WorkHandler(tantalum.shell.tool.Workspaces.shared(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))), tantalum.shell.access.PluginGate.allOn(new tantalum.shell.tool.ToolCatalog(), List.of(), List.of(), List.of(slow, broken)), TIMEOUT, clock);

        assertEquals(t0, assertInstanceOf(WorkDone.class, handler.handle(work("slow"))).startedAt());
        WorkDone failed = assertInstanceOf(WorkDone.class, handler.handle(work("broken")));
        assertTrue(failed.failed());
        assertEquals(t1, failed.startedAt());
        assertNull(assertInstanceOf(WorkDone.class, handler.handle(work("nope"))).startedAt());
        assertNull(assertInstanceOf(WorkDone.class, handler.failed(work("slow"), new IllegalStateException("x"))).startedAt());
    }
}
