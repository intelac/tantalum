package tantalum.shell.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.kernel.Limits;
import tantalum.kernel.Replay;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.mcp.TestMcpServer.Definition;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.store.SessionStore;
import tantalum.shell.web.TestServer;
import tantalum.value.command.CallModel;
import tantalum.value.event.ToolFinished;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;

/** 经过真实的环、journal、Web 和真实 MCP 协议。模型是脚本化的，先调工具，看到结果后复述。 */
@Timeout(120)
class McpIntegrationTest {

    @TempDir
    Path temp;

    private final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger ids = new AtomicInteger();

    /** 用户消息是要调的工具名和回显内容，形如 {@code mcp__t__echo who=alice}。 */
    private final ModelClient model = (prompt, chunks) -> {
        prompts.add(prompt);
        Message last = prompt.messages().getLast();
        for (ContentBlock block : last.content()) {
            if (block instanceof ToolResultBlock result) {
                return new ModelOutcome.Replied(List.of(new TextBlock("got: " + result.text())), StopReason.END_TURN, "m", null);
            }
        }
        String[] parts = ((TextBlock) last.content().getFirst()).text().split(" ");
        Json arguments = new Json.Obj(Map.of("who", new Json.Str(parts[1])));
        return new ModelOutcome.Replied(List.of(new ToolCallBlock("c" + ids.incrementAndGet(), parts[0],
                "{\"who\":\"" + parts[1] + "\"}", arguments)), StopReason.TOOL_USE, "m", null);
    };

    private TestMcpServer server() throws Exception {
        return new TestMcpServer(
                new Definition("echo", true, args -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                    }
                    return TestMcpServer.text("echo " + args.get("who"));
                }),
                new Definition("delete_all", null, args -> TestMcpServer.text("deleted")));
    }

    private static String[] args(TestMcpServer mcp) {
        return new String[] {"--tantalum.mcp.servers.t.url=" + mcp.url(), "--tantalum.mcp.servers.t.concurrency=2"};
    }

    private static void submit(TestServer server, String user, String session, String text) throws Exception {
        assertEquals(200, server.post(user, "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"" + session + "\",\"mode\":\"prompt\",\"text\":\"" + text + "\"}").statusCode());
    }

    private static String awaitEnd(TestServer server, String user, String session) throws Exception {
        String body = "";
        for (int i = 0; i < 2000 && !body.contains("endReason"); i++) {
            Thread.sleep(10);
            body = server.get(user, "/api/sessions/" + session + "/runs").body();
        }
        assertTrue(body.contains("COMPLETED"), body);
        return server.get(user, "/api/sessions/" + session + "/messages").body();
    }

    private static void awaitTool(TestServer server, String name) throws Exception {
        for (int i = 0; i < 1000; i++) {
            if (server.context.getBean(tantalum.shell.boot.Engine.class).mcpHas(name)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("tool never appeared: " + name);
    }

    @Test
    void concurrentUsersGetTheirOwnResultsAndAHiddenToolCannotRun() throws Exception {
        try (TestMcpServer mcp = server(); TestServer server = new TestServer(temp.resolve("d"), temp.resolve("w"), model, args(mcp))) {
            awaitTool(server, "mcp__t__echo");
            List<String> users = List.of("ann", "bob", "cid", "dee", "eve", "fay");
            for (String user : users) {
                server.get(user, "/api/sessions");
            }
            List<Thread> threads = new ArrayList<>();
            for (String user : users) {
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        submit(server, user, "s-" + user, "mcp__t__echo " + user);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }));
            }
            for (Thread thread : threads) {
                thread.join();
            }
            for (String user : users) {
                String messages = awaitEnd(server, user, "s-" + user);
                assertTrue(messages.contains("got: echo " + user), messages);
                for (String other : users) {
                    if (!other.equals(user)) {
                        assertFalse(messages.contains("echo " + other), user + " saw " + other);
                    }
                }
            }
            assertEquals(6, Collections.frequency(mcp.calls, "echo"));

            // 未放行的写工具：模型即使点名调用，服务器也收不到。
            submit(server, "ann", "s-hidden", "mcp__t__delete_all now");
            assertTrue(awaitEnd(server, "ann", "s-hidden").contains("Unknown tool"));
            assertFalse(mcp.calls.contains("delete_all"));
        }
    }

    @Test
    void replayAndRestartNeverCallTheServerAndStayIdenticalWhenItIsGone() throws Exception {
        Path data = temp.resolve("d2");
        Path workspace = temp.resolve("w2");
        String before;
        try (TestMcpServer mcp = server()) {
            try (TestServer server = new TestServer(data, workspace, model, args(mcp))) {
                awaitTool(server, "mcp__t__echo");
                submit(server, "ann", "s1", "mcp__t__echo ann");
                before = awaitEnd(server, "ann", "s1");
                assertTrue(before.contains("got: echo ann"), before);
            }
            mcp.stop();
            int calls = mcp.calls.size();
            assertEquals(1, calls);

            try (TestServer restarted = new TestServer(data, workspace, model, args(mcp))) {
                assertEquals(before, restarted.get("ann", "/api/sessions/s1/messages").body());
            }
            assertEquals(calls, mcp.calls.size());
            mcp.start();
        }

        List<tantalum.value.event.EventEnvelope> journal;
        journal = new SessionStore(data, EventCodec.withBuiltins()).readAll();
        assertTrue(journal.stream().anyMatch(envelope -> envelope.event() instanceof ToolFinished));
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        // 回放时的工具安全判断来自一个不认识 MCP 工具的目录，结果必须相同。
        Kernel kernel = new Kernel(table, new HookChain(List.of()), (tool, arguments) -> false, Limits.DEFAULT);
        List<Prompt> replayed = Replay.run(kernel, journal).commands().stream().filter(CallModel.class::isInstance)
                .map(command -> ((CallModel) command).prompt()).toList();
        assertEquals(prompts, replayed);
        assertTrue(replayed.getFirst().system().contains("untrusted"));
        assertTrue(replayed.getFirst().tools().stream().anyMatch(tool -> tool.name().equals("mcp__t__echo")));
        assertEquals(Role.USER, replayed.getLast().messages().getLast().role());
    }
}
