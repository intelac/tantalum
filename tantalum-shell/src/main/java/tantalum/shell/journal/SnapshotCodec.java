package tantalum.shell.journal;

import java.util.ArrayList;
import java.util.List;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 快照行与 {@link Snapshot} 互转：{@code {"snapshot":序号,"state":…,"runs":[…],"transcript":…,"trace":…}}。
 * 用结果行同一个严格的 mapper：不认识的字段是更新的版本写的，不能当作没看见。快照行全文写出，不引用前面的内容。
 */
final class SnapshotCodec {

    static final String FIELD = "snapshot";
    /** 快照行的开头：读的人不解析整行就能认出它（快照可能很大）。 */
    static final byte[] PREFIX = ("{\"" + FIELD + "\":").getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final RecordCodec records;

    SnapshotCodec(RecordCodec records) {
        this.records = records;
    }

    String encode(Snapshot snapshot) {
        ObjectNode root = records.mapper().createObjectNode();
        root.put(FIELD, snapshot.seq());
        root.set("state", records.mapper().valueToTree(snapshot.session()));
        ArrayNode runs = root.putArray("runs");
        snapshot.runs().forEach(run -> runs.add(records.run(run)));
        root.set("transcript", snapshot.transcript());
        root.set("trace", snapshot.trace());
        return records.mapper().writeValueAsString(root);
    }

    /** 坏的快照行抛 {@link IllegalArgumentException}。 */
    Snapshot decode(JsonNode tree) {
        if (!tree.path(FIELD).canConvertToLong() || !tree.path("state").isObject() || !tree.path("runs").isArray()
                || !tree.path("transcript").isObject() || !tree.path("trace").isObject()) {
            throw new IllegalArgumentException("not a snapshot");
        }
        try {
            List<RunState> runs = new ArrayList<>();
            for (JsonNode run : tree.get("runs")) {
                runs.add(records.run((ObjectNode) run));
            }
            return new Snapshot(tree.get(FIELD).asLong(), records.mapper().treeToValue(tree.get("state"), SessionState.class), runs,
                    tree.get("transcript"), tree.get("trace"));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("corrupt snapshot: " + failure.getMessage(), failure);
        }
    }

    static boolean isSnapshot(JsonNode tree) {
        return tree.has(FIELD);
    }

    /** 这一行（不含换行符）是不是快照行：只看开头。 */
    static boolean isSnapshot(byte[] line) {
        if (line.length < PREFIX.length) {
            return false;
        }
        for (int i = 0; i < PREFIX.length; i++) {
            if (line[i] != PREFIX[i]) {
                return false;
            }
        }
        return true;
    }
}
