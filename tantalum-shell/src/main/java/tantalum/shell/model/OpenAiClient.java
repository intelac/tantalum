package tantalum.shell.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tantalum.shell.json.JsonText;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.model.ToolSpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** OpenAI 兼容的流式客户端，默认指向本地 strata。 */
public final class OpenAiClient implements ModelClient {

    private static final String PROVIDER = "openai";
    private static final int MAX_ERROR_BODY = 4096;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final class ToolFragments {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final int maxTokens;
    private final Duration firstChunk;
    private final Duration betweenChunks;
    private final OverflowRules overflow;
    private final HttpClient http;

    /** 连接和响应头都受首块超时约束：看门狗只能关已拿到的响应体，服务端接受连接却不回头部时它无能为力。 */
    public OpenAiClient(String baseUrl, String model, String apiKey, int maxTokens, Duration firstChunk,
                        Duration betweenChunks, OverflowRules overflow) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        this.model = model;
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
        this.firstChunk = firstChunk;
        this.betweenChunks = betweenChunks;
        this.overflow = overflow;
        this.http = HttpClient.newBuilder().connectTimeout(firstChunk).build();
    }

    @Override
    public ModelOutcome call(Prompt prompt, ChunkListener chunks) {
        StringBuilder text = new StringBuilder();
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .header("content-type", "application/json")
                .timeout(firstChunk)
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body(prompt))));
        if (apiKey != null) {
            request.header("authorization", "Bearer " + apiKey);
        }
        InputStream[] open = new InputStream[1];
        try (StreamWatchdog watchdog = new StreamWatchdog(firstChunk, betweenChunks, () -> closeQuietly(open))) {
            try {
                HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                open[0] = response.body();
                if (response.statusCode() != 200) {
                    return httpFailure(response);
                }
                return read(response.body(), chunks, watchdog, text);
            } catch (HttpTimeoutException noHeaders) {
                return ModelFailures.timeout(text.toString());
            } catch (InterruptedException | IOException failure) {
                closeQuietly(open);
                if (ModelFailures.isInterrupt(failure)) {
                    return new ModelOutcome.Interrupted(text.toString());
                }
                if (watchdog.expired()) {
                    return ModelFailures.timeout(text.toString());
                }
                return ModelFailures.connection(text.toString(), failure.toString());
            } catch (JacksonException malformed) {
                // 畸形分片：流本身坏了，按连接错误重试，已收到的文本保留。
                closeQuietly(open);
                return ModelFailures.connection(text.toString(), "malformed stream chunk: " + malformed.getOriginalMessage());
            }
        }
    }

    private static void closeQuietly(InputStream[] open) {
        if (open[0] != null) {
            try {
                open[0].close();
            } catch (IOException ignored) {
                // 已经在结束这次调用。
            }
        }
    }

    /** 错误响应体会进日志、SSE 和 journal，只保留开头一段。 */
    private ModelOutcome httpFailure(HttpResponse<InputStream> response) throws IOException {
        String body;
        try (InputStream in = response.body()) {
            byte[] head = in.readNBytes(MAX_ERROR_BODY);
            body = new String(head, StandardCharsets.UTF_8) + (in.read() >= 0 ? "..." : "");
        }
        return ModelFailures.http(response.statusCode(), body, response.headers().firstValue("retry-after").orElse(null), "", overflow);
    }

    private ModelOutcome read(InputStream body, ChunkListener chunks, StreamWatchdog watchdog, StringBuilder text)
            throws IOException {
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, ToolFragments> tools = new TreeMap<>();
        String finishReason = null;
        String actualModel = model;
        TokenUsage usage = null;

        BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String line;
        while ((line = lines.readLine()) != null) {
            if (Thread.currentThread().isInterrupted()) {
                return new ModelOutcome.Interrupted(text.toString());
            }
            watchdog.activity();
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).trim();
            if (data.equals("[DONE]")) {
                break;
            }
            JsonNode chunk = MAPPER.readTree(data);
            if (chunk.hasNonNull("model")) {
                actualModel = chunk.get("model").asString();
            }
            if (chunk.hasNonNull("usage")) {
                usage = usage(chunk.get("usage"));
            }
            JsonNode choices = chunk.path("choices");
            if (choices.isEmpty()) {
                continue;
            }
            JsonNode choice = choices.get(0);
            if (choice.hasNonNull("finish_reason")) {
                finishReason = choice.get("finish_reason").asString();
            }
            JsonNode delta = choice.path("delta");
            if (delta.hasNonNull("reasoning_content")) {
                String piece = delta.get("reasoning_content").asString();
                reasoning.append(piece);
                chunks.onReasoning(piece);
            }
            if (delta.hasNonNull("content")) {
                String piece = delta.get("content").asString();
                text.append(piece);
                chunks.onText(piece);
            }
            for (JsonNode call : delta.path("tool_calls")) {
                ToolFragments fragments = tools.computeIfAbsent(call.path("index").asInt(), index -> new ToolFragments());
                if (call.hasNonNull("id")) {
                    fragments.id = call.get("id").asString();
                }
                JsonNode function = call.path("function");
                if (function.hasNonNull("name")) {
                    fragments.name = function.get("name").asString();
                }
                if (function.hasNonNull("arguments")) {
                    fragments.arguments.append(function.get("arguments").asString());
                }
            }
        }
        ModelOutcome cut = ModelFailures.afterStream(text.toString(), watchdog, finishReason != null);
        if (cut != null) {
            return cut;
        }
        return new ModelOutcome.Replied(content(reasoning, text, tools), stop(finishReason), actualModel, usage);
    }

    private static List<ContentBlock> content(StringBuilder reasoning, StringBuilder text, Map<Integer, ToolFragments> tools) {
        List<ContentBlock> content = new ArrayList<>();
        if (!reasoning.isEmpty()) {
            content.add(new ReasoningBlock(PROVIDER, new Json.Str(reasoning.toString())));
        }
        if (!text.isEmpty()) {
            content.add(new TextBlock(text.toString()));
        }
        for (ToolFragments tool : tools.values()) {
            String raw = tool.arguments.toString();
            content.add(ModelFailures.toolCall(tool.id, tool.name, raw));
        }
        return content;
    }

    private static StopReason stop(String finishReason) {
        return switch (finishReason) {
            case "stop" -> StopReason.END_TURN;
            case "tool_calls" -> StopReason.TOOL_USE;
            case "length" -> StopReason.MAX_TOKENS;
            default -> StopReason.OTHER;
        };
    }

    private static TokenUsage usage(JsonNode usage) {
        // prompt_tokens 已包含缓存命中；TokenUsage.input 只算未缓存的部分。
        long cached = usage.path("prompt_tokens_details").path("cached_tokens").asLong();
        return new TokenUsage(Math.max(0, usage.path("prompt_tokens").asLong() - cached), usage.path("completion_tokens").asLong(), cached, 0);
    }

    private Map<String, Object> body(Prompt prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        body.put("max_tokens", prompt.output().maxTokens() > 0 ? prompt.output().maxTokens() : maxTokens);
        List<Object> messages = new ArrayList<>();
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", prompt.system());
        messages.add(system);
        prompt.messages().forEach(message -> addMessage(messages, message));
        body.put("messages", messages);
        if (!prompt.tools().isEmpty()) {
            body.put("tools", prompt.tools().stream().map(OpenAiClient::tool).toList());
            if (!prompt.output().toolsAllowed()) {
                body.put("tool_choice", "none");
            }
        }
        return body;
    }

    private static Map<String, Object> tool(ToolSpec spec) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", spec.name());
        function.put("description", spec.description());
        function.put("parameters", JsonText.toPlain(spec.schema()));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private static void addMessage(List<Object> messages, Message message) {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<Object> calls = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            switch (block) {
                case TextBlock plain -> text.append(plain.text());
                case ReasoningBlock own when own.provider().equals(PROVIDER) && own.raw() instanceof Json.Str value ->
                        reasoning.append(value.value());
                case ReasoningBlock other -> { }
                case ToolCallBlock call -> calls.add(call(call));
                case ToolResultBlock result -> {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("role", "tool");
                    tool.put("tool_call_id", result.toolCallId());
                    tool.put("content", result.text());
                    messages.add(tool);
                }
            }
        }
        if (text.isEmpty() && reasoning.isEmpty() && calls.isEmpty()) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", message.role() == Role.USER ? "user" : "assistant");
        entry.put("content", text.toString());
        if (!reasoning.isEmpty()) {
            entry.put("reasoning_content", reasoning.toString());
        }
        if (!calls.isEmpty()) {
            entry.put("tool_calls", calls);
        }
        messages.add(entry);
    }

    private static Map<String, Object> call(ToolCallBlock call) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", call.name());
        function.put("arguments", call.argumentsValid() ? call.rawArguments() : "{}");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", call.id());
        entry.put("type", "function");
        entry.put("function", function);
        return entry;
    }
}
