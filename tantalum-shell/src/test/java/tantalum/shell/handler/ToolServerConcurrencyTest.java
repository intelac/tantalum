package tantalum.shell.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
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

/** 需求 9 第 14 条：只能单线程响应的工具服务端。 */
class ToolServerConcurrencyTest {

    private static RunTool command(String tool, String id, RunRef run) {
        return new RunTool(new CommandId(id), run, new ToolCallBlock(id, tool, "\"" + id + "\"", new Json.Str(id)));
    }

    private static RunRef run(String name) {
        return new RunRef(new Principal(name), new SessionId(name), new RunId(name));
    }

    private static List<ToolFinished> callAll(ToolHandler handler, List<RunTool> commands) throws Exception {
        List<AtomicReference<ToolFinished>> results = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (RunTool command : commands) {
            AtomicReference<ToolFinished> result = new AtomicReference<>();
            results.add(result);
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    result.set((ToolFinished) handler.handle(command));
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        return results.stream().map(AtomicReference::get).toList();
    }

    @Test
    void batchNeverExceedsTheLimitAndEachCallKeepsItsOwnResult() throws Exception {
        SingleRequestServer server = new SingleRequestServer("single");
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(server);
        ToolHandler handler = new ToolHandler(catalog, tantalum.shell.tool.Workspaces.shared(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))), tantalum.shell.access.PluginGate.allOn(catalog, List.of(), List.of(), List.of()), Duration.ofSeconds(30), java.time.Clock.systemUTC());

        List<RunTool> commands = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            commands.add(command("single", "c" + i, run("a")));
        }
        List<ToolFinished> results = callAll(handler, commands);

        assertEquals(0, server.violations.get());
        assertEquals(20, server.served.size());
        for (int i = 0; i < 20; i++) {
            assertEquals("c" + i, results.get(i).toolCallId());
            assertEquals("c" + i, results.get(i).text());
        }
    }

    @Test
    void limitIsSharedAcrossRunsAndUsers() throws Exception {
        SingleRequestServer server = new SingleRequestServer("single");
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(server);
        ToolHandler handler = new ToolHandler(catalog, tantalum.shell.tool.Workspaces.shared(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))), tantalum.shell.access.PluginGate.allOn(catalog, List.of(), List.of(), List.of()), Duration.ofSeconds(30), java.time.Clock.systemUTC());

        List<RunTool> commands = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            commands.add(command("single", "a" + i, run("userA")));
            commands.add(command("single", "b" + i, run("userB")));
        }
        callAll(handler, commands);

        assertEquals(0, server.violations.get());
    }

    @Test
    void blockedServerDoesNotDelayOtherTools() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        Tool stuck = new Tool() {
            @Override public String name() { return "stuck"; }
            @Override public String description() { return "d"; }
            @Override public Json schema() { return new Json.Null(); }
            @Override public String server() { return "stuck-server"; }
            @Override public ToolOutput run(tantalum.shell.plugin.ToolContext context, Json arguments) throws Exception {
                running.countDown();
                release.await();
                return ToolOutput.ok("late");
            }
        };
        SingleRequestServer other = new SingleRequestServer("single");
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(stuck);
        catalog.register(other);
        ToolHandler handler = new ToolHandler(catalog, tantalum.shell.tool.Workspaces.shared(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))), tantalum.shell.access.PluginGate.allOn(catalog, List.of(), List.of(), List.of()), Duration.ofSeconds(30), java.time.Clock.systemUTC());

        Thread blocked = Thread.ofVirtual().start(() -> {
            try {
                handler.handle(command("stuck", "x", run("a")));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));

        long start = System.nanoTime();
        callAll(handler, List.of(command("single", "fast", run("b"))));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5);

        release.countDown();
        blocked.join();
    }

    @Test
    void manyRoundsLeakNoPermits() throws Exception {
        SingleRequestServer server = new SingleRequestServer("single");
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(server);
        ToolHandler handler = new ToolHandler(catalog, tantalum.shell.tool.Workspaces.shared(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))), tantalum.shell.access.PluginGate.allOn(catalog, List.of(), List.of(), List.of()), Duration.ofSeconds(30), java.time.Clock.systemUTC());

        for (int round = 0; round < 20; round++) {
            callAll(handler, List.of(command("single", "r" + round + "a", run("a")),
                    command("single", "r" + round + "b", run("a"))));
        }
        for (int i = 0; i < 200 && handler.limiterFor(server).available() != 1; i++) {
            Thread.sleep(5);
        }
        assertEquals(1, handler.limiterFor(server).available());
    }
}
