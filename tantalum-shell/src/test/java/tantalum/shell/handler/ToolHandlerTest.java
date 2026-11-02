package tantalum.shell.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.tool.ToolCatalog;
import tantalum.value.command.RunTool;
import tantalum.value.event.ToolFinished;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ToolCallBlock;

class ToolHandlerTest {

    private static final RunRef RUN = new RunRef(new Principal("p"), new SessionId("s"), new RunId("r"));

    private interface Body {
        ToolOutput run() throws Exception;
    }

    private static Tool tool(Body body) {
        return new Tool() {
            @Override public String name() { return "t"; }
            @Override public String description() { return "d"; }
            @Override public Json schema() { return new Json.Null(); }
            @Override public ToolOutput run(tantalum.shell.plugin.ToolContext context, Json arguments) throws Exception { return body.run(); }
        };
    }

    private static Tool onServer(String name, String server) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "d"; }
            @Override public Json schema() { return new Json.Null(); }
            @Override public String server() { return server; }
            @Override public int serverConcurrency() { return 2; }
            @Override public ToolOutput run(tantalum.shell.plugin.ToolContext context, Json arguments) { return ToolOutput.ok(""); }
        };
    }

    @Test
    void toolsOnOneServerShareALimiter() {
        Tool a = onServer("a", "s");
        Tool b = onServer("b", "s");
        Tool c = onServer("c", "other");
        ToolHandler handler = handler(a, Duration.ofSeconds(1));
        assertTrue(handler.limiterFor(a) == handler.limiterFor(b));
        assertTrue(handler.limiterFor(a) != handler.limiterFor(c));
    }

    private static RunTool command(String name) {
        return new RunTool(new CommandId("c"), RUN, new ToolCallBlock("call", name, "{}", new Json.Null()));
    }

    private static ToolHandler handler(Tool tool, Duration timeout, AfterToolHook... hooks) {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(tool);
        return new ToolHandler(catalog, tantalum.shell.tool.Workspaces.shared(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))), List.of(hooks), timeout);
    }

    private static ToolFinished result(ToolHandler handler, String name) throws Exception {
        return assertInstanceOf(ToolFinished.class, handler.handle(command(name)));
    }

    @Test
    void successBecomesAResult() throws Exception {
        ToolFinished finished = result(handler(tool(() -> ToolOutput.ok("out")), Duration.ofSeconds(5)), "t");
        assertEquals("out", finished.text());
        assertFalse(finished.error());
        assertEquals("call", finished.toolCallId());
    }

    @Test
    void unknownToolIsAnError() throws Exception {
        assertTrue(result(handler(tool(() -> ToolOutput.ok("")), Duration.ofSeconds(5)), "nope").error());
    }

    @Test
    void exceptionBecomesAnErrorResultWithoutRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolFinished finished = result(handler(tool(() -> {
            calls.incrementAndGet();
            throw new java.io.IOException("connection refused");
        }), Duration.ofSeconds(5)), "t");
        assertTrue(finished.error());
        assertTrue(finished.text().contains("connection refused"));
        assertEquals(1, calls.get());
    }

    @Test
    void timeoutBecomesAnErrorResultAndInterruptsTheTool() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        ToolFinished finished = result(handler(tool(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException expected) {
                interrupted.countDown();
                throw expected;
            }
            return ToolOutput.ok("");
        }), Duration.ofMillis(50)), "t");
        assertTrue(finished.error());
        assertTrue(finished.text().contains("timed out"));
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
    }

    @Test
    void waitingForACapacitySlotCountsTowardsTheTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        ToolHandler handler = handler(tool(() -> {
            running.countDown();
            release.await();
            return ToolOutput.ok("");
        }), Duration.ofMillis(200));
        Thread busy = Thread.ofVirtual().start(() -> {
            try {
                handler.handle(command("t"));
            } catch (Exception ignored) {
                // 这里只需要占住名额。
            }
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));

        ToolFinished waiting = result(handler, "t");

        assertTrue(waiting.error());
        assertTrue(waiting.text().contains("timed out"));
        release.countDown();
        busy.join();
    }

    @Test
    void permitStaysHeldUntilAnInterruptIgnoringToolReallyEnds() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ToolCatalog catalog = new ToolCatalog();
        Tool stubborn = tool(() -> {
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // 这个工具故意不理会中断。
                }
            }
            return ToolOutput.ok("");
        });
        catalog.register(stubborn);
        ToolHandler handler = new ToolHandler(catalog, tantalum.shell.tool.Workspaces.shared(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))), List.of(), Duration.ofMillis(30));

        assertTrue(result(handler, "t").text().contains("timed out"));
        assertEquals(0, handler.limiterFor(stubborn).available());

        release.countDown();
        for (int i = 0; i < 500 && handler.limiterFor(stubborn).available() == 0; i++) {
            Thread.sleep(5);
        }
        assertEquals(1, handler.limiterFor(stubborn).available());
    }

    @Test
    void afterToolHookChangesOnlyTheData() throws Exception {
        AfterToolHook truncate = (context, name, arguments, output) -> ToolOutput.ok(output.text().substring(0, 2));
        assertEquals("ab", result(handler(tool(() -> ToolOutput.ok("abcdef")), Duration.ofSeconds(5), truncate), "t").text());
    }

    @Test
    void failingAfterToolHookKeepsTheOriginalOutput() throws Exception {
        AfterToolHook broken = (context, name, arguments, output) -> { throw new IllegalStateException("boom"); };
        assertEquals("abc", result(handler(tool(() -> ToolOutput.ok("abc")), Duration.ofSeconds(5), broken), "t").text());
    }

    @Test
    void interruptWhileWaitingDoesNotReachTheServer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolHandler handler = handler(tool(() -> {
            calls.incrementAndGet();
            return ToolOutput.ok("");
        }), Duration.ofSeconds(5));
        Thread.currentThread().interrupt();
        Exception failure = null;
        try {
            handler.handle(command("t"));
        } catch (InterruptedException expected) {
            failure = expected;
        }
        Thread.interrupted();
        assertInstanceOf(InterruptedException.class, failure);
        assertEquals(0, calls.get());
    }

    @Test
    void failedProducesAnErrorResultForTheCall() {
        ToolFinished finished = assertInstanceOf(ToolFinished.class,
                handler(tool(() -> ToolOutput.ok("")), Duration.ofSeconds(5)).failed(command("t"), new InterruptedException("stop")));
        assertTrue(finished.error());
        assertEquals("call", finished.toolCallId());
    }
}
