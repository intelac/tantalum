package tantalum.memory.eval;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import tantalum.shell.web.TestServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 评测用的世界：几台真服务器（按配置各一台，第一次用到时才起）共用一个脚本化模型 {@link EvalModel}。每道题用新的用户名，
 * 种子在这个人第一次被访问之前写进他的记忆账，所以题和题互不影响。
 */
final class EvalWorld implements AutoCloseable {

    /** 默认：不自动记笔记。 */
    static final String PLAIN = "plain";
    /** 自动记笔记开着。 */
    static final String CAPTURE = "capture";
    /** 自动记笔记开着、窗口很小（对话长了就压缩），参数照 CompactApiTest。 */
    static final String SMALL = "small";
    /** 自动记笔记开着，要重启的题用（重启不影响别的题）。 */
    static final String RESTART = "restart";
    /** 主窗口的题：自动记笔记开着，主窗口只留约 2000 token 最近的原话（memory-final-plan 第 11 节）。 */
    static final String MAIN = "main";

    private static final Map<String, String[]> CONFIGS = Map.of(
            PLAIN, new String[0],
            CAPTURE, new String[] {"--tantalum.memory-capture.enabled=true"},
            SMALL, new String[] {"--tantalum.memory-capture.enabled=true", "--tantalum.claude.context-window=7500",
                "--tantalum.claude.max-tokens=500", "--tantalum.claude.bytes-per-token=3", "--tantalum.compaction.reserve-tokens=500",
                "--tantalum.compaction.keep-recent-tokens=600", "--tantalum.web.fetch.enabled=false"},
            RESTART, new String[] {"--tantalum.memory-capture.enabled=true"},
            // 估算系数和脚本化模型报的用量一致（它按 3 字节一个 token 报），不然压缩点和实际大小差一倍
            MAIN, new String[] {"--tantalum.memory-capture.enabled=true", "--tantalum.compaction.main-window-tokens=2000",
                "--tantalum.claude.bytes-per-token=3"});

    /** 管理员（只读查看用）。 */
    static final String ADMIN = "root";

    final EvalModel model = new EvalModel();
    final EvalSeed seed = EvalSeed.load();
    private final Path root;
    private final Map<String, TestServer> servers = new HashMap<>();
    private final List<AutoCloseable> closing = new ArrayList<>();
    private int counter;

    EvalWorld(Path root) {
        this.root = root;
    }

    /** 题目的检查自己出了问题（不是记忆的问题）：不管题目标没标预期失败都要报出来。 */
    static final class HarnessError extends RuntimeException {
        HarnessError(String message) {
            super(message);
        }
    }

    TestServer server(String config) {
        return servers.computeIfAbsent(config, this::open);
    }

    private TestServer open(String config) {
        TestServer server = new TestServer(data(config), workspace(config), model, CONFIGS.get(config));
        try {
            if (server.users().status(ADMIN).isEmpty()) {
                server.users().add(ADMIN, TestServer.PASSWORD, true);
            }
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        return server;
    }

    /** 关掉再用同一个数据目录起来（模拟部署重启）。 */
    void restart(String config) {
        TestServer old = servers.remove(config);
        if (old != null) {
            old.close();
        }
        server(config);
    }

    Path data(String config) {
        return root.resolve(config).resolve("data");
    }

    Path workspace(String config) {
        return root.resolve(config).resolve("ws");
    }

    Path dir(String name) throws java.io.IOException {
        return Files.createDirectories(root.resolve("dirs").resolve(name + "-" + (++counter)));
    }

    /** 新的用户名；{@code seedOf} 不为 null 时把种子里这个人的条目写进他的记忆账。 */
    String person(String config, String seedOf) {
        return person(config, seedOf, Map.of());
    }

    String person(String config, String seedOf, Map<String, String> projects) {
        String name = (seedOf == null ? "user" : seedOf) + "-" + (++counter);
        if (seedOf != null) {
            try {
                seed.write(seedOf, data(config).resolve("plugins").resolve("memory").resolve(name), Instant.now(), projects);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
            String firstPage = seed.items(seedOf).getFirst().get("page").asString();
            if (!get(config, name, "/api/plugin-api/memory/entries").contains("\"" + firstPage + "\"")) {
                throw new HarnessError("the seed of " + seedOf + " was not loaded");
            }
        }
        return name;
    }

    String session() {
        return "e-" + (++counter);
    }

    /** 一个主窗口会话（编号以 {@code main-} 开头）。 */
    String mainSession() {
        return "main-e-" + (++counter);
    }

    HttpResponse<String> send(String config, String user, String method, String path, String body) {
        HttpRequest.Builder request = server(config).request(user, path).header("content-type", "application/json");
        try {
            return server(config).http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException | InterruptedException failure) {
            throw new IllegalStateException(failure);
        }
    }

    String get(String config, String user, String path) {
        HttpResponse<String> response = send(config, user, "GET", path, null);
        return response.body();
    }

    JsonNode json(String config, String user, String path) {
        return EvalSeed.JSON.readTree(get(config, user, path));
    }

    /** 问一句，等这一轮结束；返回这一轮里对话模型收到的每次输入。 */
    List<EvalModel.Call> ask(String config, String user, String session, String text) {
        return ask(config, user, session, text, Map.of());
    }

    List<EvalModel.Call> ask(String config, String user, String session, String text, Map<String, Object> extra) {
        int from = model.count();
        submit(config, user, session, text, extra);
        String end = waitEnd(config, user, session);
        if (!"COMPLETED".equals(end)) {
            throw new HarnessError("the run in " + session + " ended " + end);
        }
        return model.since(from, EvalModel.Kind.CHAT);
    }

    int runs(String config, String user, String session) {
        JsonNode runs = json(config, user, "/api/sessions/" + session + "/runs").get("runs");
        return runs == null ? 0 : runs.size();
    }

    void submit(String config, String user, String session, String text, Map<String, Object> extra) {
        int before = runs(config, user, session);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "r-" + (++counter));
        body.put("delivery", "queue");
        body.put("text", text);
        body.putAll(extra);
        HttpResponse<String> response = send(config, user, "POST", "/api/sessions/" + session + "/submissions", EvalSeed.JSON.writeValueAsString(body));
        if (response.statusCode() != 200) {
            throw new HarnessError("submit " + response.statusCode() + ": " + response.body());
        }
        if (!waitFor(() -> runs(config, user, session) > before, 10_000)) {
            throw new HarnessError("no run started in " + session);
        }
    }

    /** 等最新一轮结束，返回 endReason；30 秒没结束是检查的问题。 */
    String waitEnd(String config, String user, String session) {
        String[] end = new String[1];
        boolean ended = waitFor(() -> {
            JsonNode runs = json(config, user, "/api/sessions/" + session + "/runs").get("runs");
            JsonNode last = runs.get(runs.size() - 1);
            end[0] = last.has("endReason") ? last.get("endReason").asString() : null;
            return end[0] != null;
        }, 30_000);
        if (!ended) {
            throw new HarnessError("the run in " + session + " did not end");
        }
        return end[0];
    }

    /** 一个人记忆账里现在的条目（只读文件，不开账：服务器正在用它）：id → 条目；墓碑掉的不在里面。 */
    Map<String, JsonNode> items(String config, String user) {
        Path file = data(config).resolve("plugins").resolve("memory").resolve(user).resolve("ledger.jsonl");
        Map<String, JsonNode> items = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return items;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                JsonNode parsed;
                try {
                    parsed = EvalSeed.JSON.readTree(line);
                } catch (RuntimeException partial) {
                    continue;
                }
                if (parsed.has("put")) {
                    items.put(parsed.get("put").get("id").asString(), parsed.get("put"));
                } else if (parsed.has("tombstone")) {
                    items.remove(parsed.get("tombstone").get("id").asString());
                }
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
        return items;
    }

    /** 账里还在的条目，文字含 {@code text}。 */
    List<JsonNode> itemsWith(String config, String user, String text) {
        return items(config, user).values().stream().filter(item -> item.path("text").asString().contains(text)).toList();
    }

    static boolean waitFor(BooleanSupplier condition, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** 题目结束时一起关掉的东西（连上的测试电脑）。 */
    <T extends AutoCloseable> T closeLater(T resource) {
        closing.add(resource);
        return resource;
    }

    static String kindOf(JsonNode item) {
        return item.path("kind").asString();
    }

    static ObjectNode object(JsonNode node) {
        return (ObjectNode) node;
    }

    @Override
    public void close() {
        for (AutoCloseable resource : closing) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // 关不掉的电脑不影响结果
            }
        }
        servers.values().forEach(TestServer::close);
        servers.clear();
    }
}
