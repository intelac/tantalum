package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tantalum.local.tool.LocalResult;
import tantalum.shell.cli.CliText;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.local.TestComputer;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.web.TestServer;
import tantalum.value.event.ModelReplied;
import tantalum.value.id.Principal;
import tantalum.value.model.Prompt;
import tantalum.value.text.Language;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 录一次、回放到底（agent-eval 需求 2，照 DSH {@code snapshots/} + {@code llm-replay}）。每个场景是 {@code src/test/resources/replay/<id>/}：
 * 真模型录下的回复（会话记录里的 {@code ModelReplied} 原样）、那次的工具结果、运行后应有的文件。回放启动真的服务器和测试电脑（连接程序同一份
 * 工具实现），只把模型换成按顺序给录下的回复；{@code bash} 回录下的结果（测试电脑没有沙箱），其余工具真的执行。
 * 比对：模型调用次数一样、每次是不是摘要调用一样、真执行的工具结果一样、最后的文件一样、记录重放一致。录制见 {@code scenarios/run.py --record}。
 */
class ReplayScenarioTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path REPLAY = Path.of("src/test/resources/replay");
    private static final EventCodec CODEC = EventCodec.withBuiltins();
    static final String MAC_CONNECTOR = "连接程序目前只在 macOS 上运行：Windows 上工具结果里的路径用 \\ 分隔，录制是在 macOS 上做的";

    @TempDir
    Path temp;

    private static final String NONE = "(no recorded scenario)";

    /** 一个都没录时给一个占位，测试跳过并写明原因（参数化测试没有参数会报错，不会跳过）。 */
    static Stream<String> scenarios() throws IOException {
        if (!Files.isDirectory(REPLAY)) {
            return Stream.of(NONE);
        }
        String only = System.getProperty("replay.only", "");
        try (Stream<Path> dirs = Files.list(REPLAY)) {
            List<String> ids = dirs.filter(dir -> Files.isRegularFile(dir.resolve("scenario.json"))).map(dir -> dir.getFileName().toString())
                    .filter(id -> only.isBlank() || List.of(only.split(",")).contains(id)).sorted().toList();
            return ids.isEmpty() ? Stream.of(NONE) : ids.stream();
        }
    }

    /** 录下的一次模型回复：会话记录里那一行。 */
    private record Reply(ModelReplied replied, boolean summary) {}

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void replaysAsRecorded(String id) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(!id.equals(NONE), "nothing recorded yet: run scenarios/run.py --model claude --record");
        assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"), MAC_CONNECTOR);
        Path dir = REPLAY.resolve(id);
        JsonNode scenario = JSON.readTree(Files.readString(dir.resolve("scenario.json")));
        Path root = Files.createDirectories(temp.resolve("work")).toRealPath().resolve("inventory");
        seed(root, dir);
        String rootText = root.toString();
        String escaped = JSON.writeValueAsString(rootText);
        escaped = escaped.substring(1, escaped.length() - 1);

        List<Reply> replies = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("replies.jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode entry = JSON.readTree(line.replace("{{root}}", escaped));
            ModelReplied replied = (ModelReplied) CODEC.decode(entry.get("type").asString(), entry.get("version").asInt(), entry.get("payload"));
            replies.add(new Reply(replied, replied.options() != null && !replied.options().toolsAllowed()));
        }
        List<JsonNode> tools = new ArrayList<>();
        Deque<LocalResult> shell = new ArrayDeque<>();
        for (String line : Files.readAllLines(dir.resolve("tools.jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode tool = JSON.readTree(line);
            tools.add(tool);
            if (tool.get("name").asString().equals("bash")) {
                String text = tool.get("text").asString().replace("{{root}}", rootText);
                shell.add(tool.get("error").asBoolean() ? LocalResult.error(text) : LocalResult.ok(text));
            }
        }

        AtomicInteger next = new AtomicInteger();
        List<String> problems = java.util.Collections.synchronizedList(new ArrayList<>());
        ModelClient model = (Prompt prompt, tantalum.shell.model.ChunkListener chunks) -> {
            int index = next.getAndIncrement();
            if (index >= replies.size()) {
                problems.add("replies ran out: the product called the model " + (index + 1) + " times, " + replies.size()
                        + " were recorded; record " + id + " again");
                return new ModelOutcome.Replied(List.of(new tantalum.value.model.TextBlock("(replay ran out)")),
                        tantalum.value.model.StopReason.END_TURN, "replay", null);
            }
            Reply reply = replies.get(index);
            boolean summary = !prompt.output().toolsAllowed();
            if (summary != reply.summary()) {
                problems.add("call " + (index + 1) + " was " + (summary ? "a compaction summary" : "a normal call") + " but the recording has "
                        + (reply.summary() ? "a compaction summary" : "a normal call") + " there: compaction now happens at a different point");
            }
            ModelReplied replied = reply.replied();
            return new ModelOutcome.Replied(replied.content(), replied.stop(), replied.actualModel(), replied.usage());
        };

        List<String> extra = new ArrayList<>();
        scenario.get("serverArgs").forEach(arg -> extra.add(arg.asString()));
        Path data = temp.resolve("data");
        try (TestServer server = new TestServer(data, temp.resolve("ws"), model, extra.toArray(String[]::new));
             TestComputer ignored = TestComputer.connectForReplay(server.context.getBean(Engine.class).localLinks(), new Principal("alice"),
                     Instant.now().plus(Duration.ofDays(1)), root, scenario.get("write").asBoolean(), shell)) {
            String body = JSON.writeValueAsString(Map.of("requestId", "r1", "mode", "prompt", "text", scenario.get("prompt").asString()));
            assertEquals(200, server.post("alice", "/api/sessions/s1/submissions", body).statusCode());
            Deque<JsonNode> answers = new ArrayDeque<>();
            scenario.path("answers").forEach(answers::add);
            waitForEnd(server, answers);
        }

        if (next.get() < replies.size()) {
            problems.add("recorded replies were not used up: the product called the model " + next.get() + " times, " + replies.size() + " were recorded");
        }
        problems.addAll(compareTools(data.resolve("sessions/alice/s1.jsonl"), tools, rootText));
        problems.addAll(compareFiles(root, dir));
        if (!problems.isEmpty()) {
            fail(id + " no longer replays as recorded:\n- " + String.join("\n- ", problems));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = RecordCheckCommand.run(new String[] {"--tantalum.data-dir=" + data, "--tantalum.workspace=" + temp.resolve("ws")},
                new PrintStream(out, true, StandardCharsets.UTF_8), CliText.of(Language.EN));
        assertEquals(0, code, out.toString(StandardCharsets.UTF_8));
    }

    private static void waitForEnd(TestServer server, Deque<JsonNode> answers) throws Exception {
        for (int i = 0; i < 6000; i++) {
            JsonNode runs = JSON.readTree(server.get("alice", "/api/sessions/s1/runs").body()).get("runs");
            if (runs != null && !runs.isEmpty() && runs.get(runs.size() - 1).has("endReason")) {
                assertEquals("COMPLETED", runs.get(runs.size() - 1).get("endReason").asString(), runs.toString());
                return;
            }
            JsonNode questions = JSON.readTree(server.get("alice", "/api/sessions/s1/questions").body()).path("questions");
            for (JsonNode question : questions) {
                JsonNode answer = answers.pollFirst();
                if (answer != null) {
                    server.post("alice", "/api/sessions/s1/questions/" + question.get("id").asString(), JSON.writeValueAsString(answer));
                }
            }
            Thread.sleep(20);
        }
        fail("the run did not end within two minutes");
    }

    /** 真执行的工具结果和录下的一样（路径换成 {{root}}）；bash 是录下的结果喂回去的，只比名字和顺序。 */
    private static List<String> compareTools(Path journal, List<JsonNode> recorded, String root) throws IOException {
        Map<String, String> names = new LinkedHashMap<>();
        List<String[]> results = new ArrayList<>();
        for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
            JsonNode entry = JSON.readTree(line);
            JsonNode payload = entry.path("payload");
            switch (entry.path("type").asString("")) {
                case "ModelReplied" -> payload.path("content").forEach(block -> {
                    if (block.path("kind").asString("").equals("toolCall")) names.put(block.get("id").asString(), block.get("name").asString());
                });
                case "ToolFinished" -> results.add(new String[] {names.getOrDefault(payload.get("toolCallId").asString(), "?"),
                        payload.path("text").asString("").replace(root, "{{root}}"), String.valueOf(payload.path("error").asBoolean())});
                default -> { }
            }
        }
        List<String> problems = new ArrayList<>();
        if (results.size() != recorded.size()) {
            problems.add(results.size() + " tool results, " + recorded.size() + " recorded");
        }
        for (int i = 0; i < Math.min(results.size(), recorded.size()); i++) {
            String[] now = results.get(i);
            JsonNode then = recorded.get(i);
            if (!now[0].equals(then.get("name").asString())) {
                problems.add("tool " + (i + 1) + " is " + now[0] + ", recorded " + then.get("name").asString());
            } else if (!now[1].equals(then.get("text").asString()) || !now[2].equals(String.valueOf(then.get("error").asBoolean()))) {
                problems.add("tool " + (i + 1) + " (" + now[0] + ") gives a different result now:\n    recorded: "
                        + shorten(then.get("text").asString()) + "\n    now:      " + shorten(now[1]));
            }
        }
        return problems;
    }

    /** 运行后：expected/ 里的文件逐字节相同，expected-deleted.txt 列的不在了，别的和开始时一样。 */
    private List<String> compareFiles(Path root, Path dir) throws IOException, InterruptedException {
        Path start = Files.createDirectories(temp.resolve("start")).resolve("inventory");
        seed(start, dir);
        Map<String, byte[]> expected = snapshot(start);
        Path changed = dir.resolve("expected");
        if (Files.isDirectory(changed)) {
            expected.putAll(snapshot(changed));
        }
        Path deleted = dir.resolve("expected-deleted.txt");
        if (Files.isRegularFile(deleted)) {
            Files.readAllLines(deleted, StandardCharsets.UTF_8).stream().filter(line -> !line.isBlank()).forEach(expected::remove);
        }
        Map<String, byte[]> actual = snapshot(root);
        List<String> problems = new ArrayList<>();
        for (String path : expected.keySet()) {
            if (!actual.containsKey(path)) problems.add("missing after the run: " + path);
            else if (!java.util.Arrays.equals(expected.get(path), actual.get(path))) problems.add("different after the run: " + path);
        }
        for (String path : actual.keySet()) {
            if (!expected.containsKey(path)) problems.add("unexpected file after the run: " + path);
        }
        return problems;
    }

    /** 种子项目：从 bundle 克隆（不转换换行），铺上没提交的改动，再铺上这个场景的准备。 */
    private static void seed(Path root, Path dir) throws IOException, InterruptedException {
        git(root.getParent(), "-c", "core.autocrlf=false", "clone", "-q", REPLAY.resolve("project.bundle").toAbsolutePath().toString(), root.getFileName().toString());
        git(root, "config", "core.autocrlf", "false");
        overlay(REPLAY.resolve("project-dirty"), root);
        overlay(dir.resolve("setup"), root);
        Path removed = dir.resolve("setup-deleted.txt");
        if (Files.isRegularFile(removed)) {
            for (String line : Files.readAllLines(removed, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) Files.deleteIfExists(root.resolve(line));
            }
        }
    }

    private static void overlay(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return;
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(from.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String path = root.relativize(file).toString().replace('\\', '/');
                if (path.startsWith(".git/") || path.contains("__pycache__/")) continue;
                try {
                    files.put(path, Files.readAllBytes(file));
                } catch (IOException unreadable) {
                    throw new UncheckedIOException(unreadable);
                }
            }
        }
        return files;
    }

    private static void git(Path dir, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0, "git " + String.join(" ", arguments) + ": " + output);
    }

    private static String shorten(String text) {
        String one = text.replace("\n", "⏎");
        return one.length() > 300 ? one.substring(0, 300) + "…" : one;
    }
}
