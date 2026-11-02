package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.kernel.Replay;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.table.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.store.SessionStore;
import tantalum.shell.web.TestServer;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.RunId;
import tantalum.value.state.Awaiting;
import tantalum.value.state.KernelState;

/** 需求 4 第 4 条：真的杀掉另一个 JVM，再从 journal 重建。 */
class CrashRestartTest {

    private static final String TOOL_REPLY = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
            + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}]},\"finish_reason\":null}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";

    @TempDir
    Path temp;

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void killedProcessRestartsFromTheLastDurableEventWithoutRepeatingWork() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Files.createDirectory(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/a.txt"), "hello");
        Path data = temp.resolve("data");

        AtomicInteger modelCalls = new AtomicInteger();
        CountDownLatch secondCall = new CountDownLatch(1);
        HttpServer fakeModel = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeModel.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        fakeModel.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (modelCalls.incrementAndGet() > 1) {
                secondCall.countDown();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException stopping) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            byte[] bytes = TOOL_REPLY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fakeModel.start();

        int port = freePort();
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                "tantalum.shell.boot.TantalumApplication",
                "--server.port=" + port,
                "--tantalum.data-dir=" + data,
                "--tantalum.workspace=" + workspace,
                "--tantalum.provider=openai",
                "--tantalum.openai.base-url=http://127.0.0.1:" + fakeModel.getAddress().getPort() + "/v1")
                .redirectErrorStream(true).redirectOutput(temp.resolve("child.log").toFile());
        builder.environment().put("TANTALUM_ADMIN_USER", "alice");
        builder.environment().put("TANTALUM_ADMIN_PASSWORD", TestServer.PASSWORD);
        Process child = builder.start();
        try {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;
            HttpRequest login = HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"alice\",\"password\":\"" + TestServer.PASSWORD + "\"}")).build();
            HttpResponse<String> loggedIn = null;
            for (int i = 0; i < 300 && loggedIn == null; i++) {
                try {
                    loggedIn = http.send(login, HttpResponse.BodyHandlers.ofString());
                } catch (java.net.ConnectException notYet) {
                    Thread.sleep(100);
                }
            }
            String cookie = loggedIn.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
            HttpRequest submit = HttpRequest.newBuilder(URI.create(base + "/api/sessions/s1/submissions"))
                    .header("Cookie", cookie).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"read it\"}")).build();
            HttpResponse<String> response = http.send(submit, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), () -> "child log at " + temp.resolve("child.log"));
            assertTrue(secondCall.await(30, TimeUnit.SECONDS), "model should be called a second time");

            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));

            int callsBeforeRestart = modelCalls.get();

            List<EventEnvelope> events;
            events = new SessionStore(data, EventCodec.withBuiltins()).readAll();
            assertEquals(List.of("UserPrompt", "ContextAssembled", "ModelReplied", "ToolFinished"),
                    events.stream().map(envelope -> envelope.event().type()).toList());
            TransitionTable table = new TransitionTable();
            BuiltinSteps.register(table);
            KernelState replayed = Replay.run(new Kernel(table, new HookChain(List.of()), (tool, args) -> false, 10), events).state();
            RunId run = replayed.runs().keySet().iterator().next();
            assertEquals("AwaitingModel", ((Awaiting) replayed.runs().get(run).phase()).resumeAt());

            ScriptedModelClient mustNotRun = new ScriptedModelClient(List.of());
            try (TestServer restarted = new TestServer(data, workspace, mustNotRun)) {
                String view = restarted.get("alice", "/api/runs/" + run.value()).body();
                assertTrue(view.contains("\"phase\":\"AwaitingModel\""), view);
                assertTrue(view.contains("\"pendingRecovery\":true"), view);
                Thread.sleep(200);
                assertEquals(0, mustNotRun.prompts.size());
                assertEquals(callsBeforeRestart, modelCalls.get());

                assertEquals(200, restarted.post("alice", "/api/runs/" + run.value() + "/cancel", "{}").statusCode());
                String ended = "";
                for (int i = 0; i < 500 && !ended.contains("CANCELLED"); i++) {
                    Thread.sleep(10);
                    ended = restarted.get("alice", "/api/runs/" + run.value()).body();
                }
                assertTrue(ended.contains("CANCELLED"), ended);
            }
        } finally {
            child.destroyForcibly();
            fakeModel.stop(0);
        }
    }
}
