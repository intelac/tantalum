package tantalum.memory.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 记忆评测的种子（{@code memory-eval/seed.json}）：写成记忆账的行，放进一个人的插件目录。两层评测共用这份种子，
 * 第二层的 Python（{@code memory_eval.py} 的 {@code ledger_lines}）按同样的规则写。
 */
final class EvalSeed {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private final JsonNode users;

    private EvalSeed(JsonNode users) {
        this.users = users;
    }

    static EvalSeed load() {
        try (InputStream in = EvalSeed.class.getResourceAsStream("/memory-eval/seed.json")) {
            return new EvalSeed(JSON.readTree(in).get("users"));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** 种子里一个人的条目（seed.json 的原样）。 */
    List<JsonNode> items(String person) {
        List<JsonNode> items = new ArrayList<>();
        users.get(person).forEach(items::add);
        return items;
    }

    /** 种子里某一条的 text。 */
    String text(String id) {
        for (JsonNode person : users) {
            for (JsonNode item : person) {
                if (item.get("id").asString().equals(id)) {
                    return item.path("text").asString();
                }
            }
        }
        throw new IllegalArgumentException("no seed item " + id);
    }

    /**
     * 把 {@code person} 的种子写进 {@code stateDir/ledger.jsonl}（插件第一次打开这个人的账之前）。
     * {@code daysAgo} 从 {@code now} 倒推；scope 里的 {@code {名字}} 换成 {@code projects} 里的路径。
     */
    void write(String person, Path stateDir, Instant now, Map<String, String> projects) throws IOException {
        Files.createDirectories(stateDir);
        StringBuilder out = new StringBuilder();
        for (JsonNode item : items(person)) {
            Instant at = now.minus(Duration.ofDays(item.path("daysAgo").asInt(1)));
            String kind = item.get("kind").asString();
            String source = item.path("source").asString("user_said");
            ObjectNode attrs = item.has("attrs") ? ((ObjectNode) item.get("attrs")).deepCopy() : JSON.createObjectNode();
            if (kind.equals("page")) {
                attrs.put("type", item.get("type").asString());
                attrs.put("description", item.get("description").asString());
            }
            if (kind.equals("card") && !attrs.has("day")) {
                attrs.put("day", at.atZone(ZoneOffset.UTC).toLocalDate().toString());
            }
            String scope = item.path("scope").asString("personal");
            for (Map.Entry<String, String> project : projects.entrySet()) {
                scope = scope.replace("{" + project.getKey() + "}", project.getValue());
            }
            ObjectNode put = JSON.createObjectNode();
            put.put("id", item.get("id").asString());
            put.put("version", 1);
            put.put("state", item.path("state").asString("confirmed"));
            put.put("kind", kind);
            if (item.has("page")) {
                put.put("page", item.get("page").asString());
            }
            put.put("scope", scope);
            put.put("source", source);
            put.put("actor", source.equals("user_said") ? "user" : "agent");
            put.put("validFrom", at.toString());
            put.put("confidence", source.equals("user_said") ? "observed" : "inferred");
            if (item.has("derivedFrom")) {
                put.set("derivedFrom", item.get("derivedFrom").deepCopy());
            }
            if (item.path("pendingConfirm").asBoolean()) {
                put.put("pendingConfirm", true);
            }
            if (item.has("status")) {
                put.put("status", item.get("status").asString());
            }
            put.put("text", item.path("text").asString(""));
            if (!attrs.isEmpty()) {
                put.set("attrs", attrs);
            }
            put.put("created", at.toString());
            put.put("updated", at.toString());
            ObjectNode line = JSON.createObjectNode();
            line.set("put", put);
            out.append(JSON.writeValueAsString(line)).append('\n');
        }
        Files.writeString(stateDir.resolve("ledger.jsonl"), out.toString(), StandardCharsets.UTF_8);
    }
}
