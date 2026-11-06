package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.boot.Engine;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ClaudeClient;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.model.OpenAiClient;
import tantalum.shell.model.OpenAiThinking;
import tantalum.shell.model.OverflowRules;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.route.RouteClients;
import tantalum.shell.store.SessionStore;
import tantalum.shell.web.TestServer;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.Prompt;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.RequestOptions;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;

/**
 * model-picker 任务 17（M9、M11，需求 6.5）：真实的 Claude（严格思考校验 {@code prefix_mismatch_behavior: error}）和本机 qwen
 * （llama.cpp，{@code chat-template}）在同一个会话里换着用。风险：换模型后 Claude 因为历史（别的模型的思考块、换模型的说明）
 * 拒绝请求，或者丢了上下文。五步：Sonnet 5.5 调工具 → Opus 5.5（高）调工具 → qwen（关）调工具 → Sonnet 5.5（低）→
 * Haiku 5.5（低；连了测试电脑，会话没有工具变化，可以选）。判定：没有一次调用失败；每次发给 Claude 的历史里带着之前所有
 * Claude 思考块；发给 qwen 的请求体里没有 Claude 的推理内容；第一步让模型记住的暗号最后一步答对。每一步的模型、档位、调用数、
 * 用量和用时打印出来，记进 tech.md。会产生费用：同时设置 {@code ANTHROPIC_API_KEY} 和 {@code TANTALUM_SMOKE_CLAUDE} 才运行，
 * 本机 8090 要有 qwen。预计 12 次调用左右。
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TANTALUM_SMOKE_CLAUDE", matches = ".+")
class ModelSwitchScenario {

    private static final String QWEN = "qwen3.8-flash-next-unsloth-ud-q4_k_xl";
    private static final String SECRET = "cobalt-4417";
    private static final Principal YI = new Principal("alice");

    @TempDir
    Path temp;

    /** 记下每次调用：路线、选择、发出时历史里有几个 Claude 思考块、结果的用量。 */
    private record Call(String route, ModelChoice model, long claudeThinking, ModelOutcome outcome, long millis) {}

    private static final class Recording implements ModelClient {
        private final String route;
        private final ModelClient real;
        private final List<Call> calls;

        Recording(String route, ModelClient real, List<Call> calls) {
            this.route = route;
            this.real = real;
            this.calls = calls;
        }

        @Override
        public ModelOutcome call(Prompt prompt, ChunkListener chunks) {
            return call(prompt, tantalum.shell.model.AttachmentMedia.NONE, chunks);
        }

        @Override
        public ModelOutcome call(Prompt prompt, tantalum.shell.model.AttachmentMedia media, ChunkListener chunks) {
            long thinking = prompt.messages().stream().flatMap(message -> message.content().stream())
                    .filter(block -> block instanceof ReasoningBlock reasoning && reasoning.provider().equals("claude")).count();
            long started = System.nanoTime();
            ModelOutcome outcome = real.call(prompt, media, chunks);
            calls.add(new Call(route, prompt.model(), thinking, outcome, Duration.ofNanos(System.nanoTime() - started).toMillis()));
            return outcome;
        }

        @Override
        public RequestOptions options(Prompt prompt) {
            return real.options(prompt);
        }
    }

    /** 本机 qwen 前面的一层转发：记下请求体，原样转给 8090。 */
    private static HttpServer recordingProxy(List<String> bodies) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            bodies.add(new String(body, StandardCharsets.UTF_8));
            try {
                HttpRequest.Builder forward = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8090" + exchange.getRequestURI()))
                        .header("content-type", "application/json");
                forward = exchange.getRequestMethod().equals("POST") ? forward.POST(HttpRequest.BodyPublishers.ofByteArray(body)) : forward.GET();
                HttpResponse<byte[]> answer = http.send(forward.build(), HttpResponse.BodyHandlers.ofByteArray());
                exchange.getResponseHeaders().set("content-type", answer.headers().firstValue("content-type").orElse("application/json"));
                exchange.sendResponseHeaders(answer.statusCode(), answer.body().length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(answer.body());
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        proxy.start();
        return proxy;
    }

    private static Tool lookup() {
        return new Tool() {
            @Override public String name() { return "lookup"; }
            @Override public String description() { return "Look up the value stored under a key."; }
            @Override public Json schema() {
                return new Json.Obj(Map.of("type", new Json.Str("object"), "properties",
                        new Json.Obj(Map.of("key", new Json.Obj(Map.of("type", new Json.Str("string"))))),
                        "required", new Json.Arr(List.of(new Json.Str("key")))));
            }
            @Override public ToolOutput run(tantalum.shell.plugin.ToolContext context, Json arguments) {
                return ToolOutput.ok("value-for-" + arguments);
            }
        };
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void switchingBetweenClaudeModelsAndQwenKeepsTheThinkingValid() throws Exception {
        List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        List<String> qwenBodies = Collections.synchronizedList(new ArrayList<>());
        HttpServer proxy = recordingProxy(qwenBodies);
        ClaudeClient claude = new ClaudeClient(AnthropicOkHttpClient.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).maxRetries(0).build(),
                new ClaudeClient.Settings("claude-sonnet-5-5", 8192, "medium", false, Duration.ofSeconds(300), Duration.ofSeconds(300),
                        OverflowRules.defaults(), true));
        OpenAiClient qwen = new OpenAiClient("http://127.0.0.1:" + proxy.getAddress().getPort() + "/v1", QWEN, null, 4096,
                Duration.ofSeconds(300), Duration.ofSeconds(60), OverflowRules.defaults(), model -> false, model -> OpenAiThinking.CHAT_TEMPLATE);
        Map<String, ModelClient> clients = Map.of("claude", new Recording("claude", claude, calls), "local", new Recording("local", qwen, calls));
        Path data = temp.resolve("data");
        Path workspace = Files.createDirectories(temp.resolve("ws/alice"));
        try (TestServer server = new TestServer(data, workspace.getParent(), clients.get("claude"), context -> {
            context.registerBean(RouteClients.class, () -> new RouteClients(clients));
            context.registerBean("lookup", Tool.class, ModelSwitchScenario::lookup);
        }, "--tantalum.claude.models=claude-sonnet-5-5,claude-opus-5-5,claude-haiku-5-5",
                "--tantalum.claude.context-window=1000000",
                "--tantalum.openai-routes.local.base-url=http://127.0.0.1:" + proxy.getAddress().getPort() + "/v1",
                "--tantalum.openai-routes.local.model=" + QWEN,
                "--tantalum.openai-routes.local.thinking=chat-template",
                "--tantalum.openai-routes.local.context-window=131072")) {
            // 连上测试电脑：本机工作区的工具一开始就开着，会话没有工具变化，Haiku 5.5 才能选（M11）
            server.connectComputer(YI.value(), workspace, false);
            Engine engine = server.context.getBean(Engine.class);
            String[][] steps = {
                {"claude", "claude-sonnet-5-5", "medium", "Remember this secret word for later: " + SECRET + ". Then call the lookup tool with key \"a\" and tell me the value."},
                {"claude", "claude-opus-5-5", "high", "Call the lookup tool with key \"b\" and tell me the value."},
                {"local", QWEN, "off", "Call the lookup tool with key \"c\" and tell me the value."},
                {"claude", "claude-sonnet-5-5", "low", "In one sentence: which keys have we looked up so far?"},
                {"claude", "claude-haiku-5-5", "low", "What was the secret word I asked you to remember? Answer with the word only."},
            };
            for (int i = 0; i < steps.length; i++) {
                String[] step = steps[i];
                int before = calls.size();
                assertEquals(200, engine.intake().submit(YI, new SessionId("s1"), new RequestId("r" + i), "prompt", step[3], List.of(),
                        new ModelChoice(step[0], step[1], step[2])).status());
                awaitRuns(server, i + 1);
                List<Call> made = calls.subList(before, calls.size());
                long input = 0, output = 0, read = 0, write = 0, millis = 0;
                for (Call call : made) {
                    millis += call.millis();
                    if (call.outcome() instanceof ModelOutcome.Replied reply && reply.usage() != null) {
                        TokenUsage usage = reply.usage();
                        input += usage.input();
                        output += usage.output();
                        read += usage.cacheRead();
                        write += usage.cacheWrite();
                    }
                }
                System.out.printf("step %d %s/%s %s: %d calls, input %d, output %d, cache read %d, cache write %d, %d ms%n",
                        i + 1, step[0], step[1], step[2], made.size(), input, output, read, write, millis);
            }
            for (Call call : calls) {
                assertFalse(call.outcome() instanceof ModelOutcome.Failed, "a call failed: " + call);
            }
            // 每次发给 Claude 的历史里带着之前所有 Claude 回复里的思考块（M9）
            long thinkingSoFar = 0;
            for (Call call : calls) {
                if (call.route().equals("claude")) {
                    assertEquals(thinkingSoFar, call.claudeThinking(), "Claude thinking dropped before " + call.model());
                }
                if (call.outcome() instanceof ModelOutcome.Replied reply) {
                    thinkingSoFar += reply.content().stream()
                            .filter(block -> block instanceof ReasoningBlock reasoning && reasoning.provider().equals("claude")).count();
                }
            }
            assertTrue(thinkingSoFar > 0, "Claude should have thought at least once");
            // 发给 qwen 的请求体里没有 Claude 的思考（签名是 Claude 思考块才有的字段）
            List<String> chats = qwenBodies.stream().filter(body -> body.contains("\"messages\"")).toList();
            assertFalse(chats.isEmpty());
            chats.forEach(body -> assertFalse(body.contains("signature"), "Claude reasoning reached qwen"));
            chats.forEach(body -> assertTrue(body.contains("\"chat_template_kwargs\":{\"enable_thinking\":false}"), body));
            // 暗号最后一步答对
            ModelOutcome.Replied last = (ModelOutcome.Replied) calls.getLast().outcome();
            String answer = last.content().stream().filter(TextBlock.class::isInstance).map(block -> ((TextBlock) block).text())
                    .reduce("", String::concat);
            assertTrue(answer.contains(SECRET), answer);
            // journal 里没有失败的调用
            List<EventEnvelope> journal = new SessionStore(data, EventCodec.withBuiltins()).readAll();
            assertEquals(0, journal.stream().filter(envelope -> envelope.event() instanceof ModelFailed).count());
            assertEquals(calls.size(), journal.stream().filter(envelope -> envelope.event() instanceof ModelReplied).count());
        } finally {
            proxy.stop(0);
        }
    }

    private static void awaitRuns(TestServer server, int count) throws Exception {
        String body = "";
        for (int i = 0; i < 6000; i++) {
            body = server.get(YI.value(), "/api/sessions/s1/runs").body();
            if (body.split("\"endReason\"", -1).length - 1 >= count) {
                assertTrue(body.split("COMPLETED", -1).length - 1 >= count, body);
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("run " + count + " did not end: " + body);
    }
}
