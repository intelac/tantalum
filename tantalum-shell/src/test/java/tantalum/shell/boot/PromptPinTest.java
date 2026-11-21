package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.local.TestComputer;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.web.TestServer;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolSpec;

/**
 * 模型拿到的东西钉住（agent-eval 需求 3，照 DSH {@code system-prompt.expected.md}、{@code tool-schemas.expected.json}）：系统提示和全部工具定义
 * 各存一份审过的文件，改了就失败，在差异里看得见；有意改时用 {@code -Dwire.record=true} 重新生成，在提交里写明改了什么。
 * 三种组合：{@code full}（电脑什么都给）、{@code read-only}（电脑不给写、没有命令行和浏览器）、{@code no-computer}（没连电脑）。
 * 允许的目录换成 {@code {{root}}}。
 */
class PromptPinTest {

    @TempDir
    Path temp;

    private final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());
    private final ModelClient model = (prompt, chunks) -> {
        prompts.add(prompt);
        return new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);
    };
    private TestServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private Prompt firstPrompt(String computer) throws Exception {
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model);
        Path mac = Files.createDirectories(temp.resolve("mac")).toRealPath();
        TestComputer connected = switch (computer) {
            case "full" -> TestComputer.connectEverything(server.context.getBean(Engine.class).localLinks(), new Principal("alice"),
                    Instant.now().plus(Duration.ofDays(1)), mac);
            case "read-only" -> server.connectComputer("alice", mac, false);
            default -> null;
        };
        try {
            assertEquals(200, server.post("alice", "/api/sessions/s1/submissions",
                    "{\"requestId\":\"r1\",\"delivery\":\"queue\",\"text\":\"hello\"}").statusCode());
            for (int i = 0; i < 1000 && prompts.isEmpty(); i++) {
                Thread.sleep(10);
            }
            Prompt prompt = prompts.getFirst();
            String root = mac.toString();
            return new Prompt(prompt.system().replace(root, "{{root}}").replaceAll("Today is \\d{4}-\\d{2}-\\d{2} \\([A-Za-z]+, time zone [^)]+\\)\\.", "Today is {{today}}."), prompt.tools(), prompt.messages(), prompt.output(), prompt.model());
        } finally {
            if (connected != null) {
                connected.close();
            }
        }
    }

    @Test
    void full() throws Exception {
        pin("full", firstPrompt("full"));
    }

    @Test
    void readOnly() throws Exception {
        pin("read-only", firstPrompt("read-only"));
    }

    @Test
    void noComputer() throws Exception {
        pin("no-computer", firstPrompt("none"));
    }

    private static void pin(String name, Prompt prompt) throws IOException {
        check("prompt-" + name + ".txt", prompt.system() + "\n");
        StringBuilder tools = new StringBuilder("[\n");
        List<ToolSpec> sorted = prompt.tools().stream().sorted(Comparator.comparing(ToolSpec::name)).toList();
        for (int i = 0; i < sorted.size(); i++) {
            ToolSpec tool = sorted.get(i);
            tools.append("  {\n    \"name\": ").append(string(tool.name())).append(",\n    \"deferred\": ").append(tool.deferred())
                    .append(",\n    \"description\": ").append(string(tool.description())).append(",\n    \"schema\": ")
                    .append(json(tool.schema(), "    ")).append("\n  }").append(i < sorted.size() - 1 ? "," : "").append('\n');
        }
        check("tools-" + name + ".json", tools.append("]\n").toString());
    }

    private static void check(String name, String actual) throws IOException {
        Path file = Path.of("src/test/resources/wire").resolve(name);
        if (Boolean.getBoolean("wire.record") || !Files.exists(file)) {
            Files.writeString(file, actual, StandardCharsets.UTF_8);
        }
        assertEquals(Files.readString(file, StandardCharsets.UTF_8), actual,
                name + " changed: what the model receives is different. If that is intended, record it again with -Dwire.record=true"
                        + " and say in the commit what changed and why.");
    }

    /** 缩进两格、键的顺序照原样的 JSON；换行一律 LF。 */
    private static String json(Json value, String indent) {
        return switch (value) {
            case Json.Null ignored -> "null";
            case Json.Bool bool -> String.valueOf(bool.value());
            case Json.Num number -> number.value().toPlainString();
            case Json.Str text -> string(text.value());
            case Json.Arr array -> {
                if (array.items().isEmpty()) {
                    yield "[]";
                }
                List<String> items = array.items().stream().map(item -> indent + "  " + json(item, indent + "  ")).toList();
                yield "[\n" + String.join(",\n", items) + "\n" + indent + "]";
            }
            case Json.Obj object -> {
                if (object.fields().isEmpty()) {
                    yield "{}";
                }
                List<String> fields = new ArrayList<>();
                for (Map.Entry<String, Json> field : object.fields().entrySet()) {
                    fields.add(indent + "  " + string(field.getKey()) + ": " + json(field.getValue(), indent + "  "));
                }
                yield "{\n" + String.join(",\n", fields) + "\n" + indent + "}";
            }
        };
    }

    private static String string(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
