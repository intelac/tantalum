package tantalum.shell.journal;

import java.util.Map;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 结果行里重复的运行引用（从 {@code RecordCodec} 拆出）：同一步的运行、命令、判断几乎都指向同一个运行，
 * 顶层记一份最先出现的，其余相同的写成 {@code "\u0000="}；运行属于这个文件的会话时顶层只写运行 ID。
 */
final class RunRefs {

    private static final String SAME_RUN = TextSlices.MARK + "=";

    private final JsonMapper mapper;

    RunRefs(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** 在 {@link TextSlices#shrink} 之后做，所以这个标记不会被转义。 */
    void collapse(ObjectNode root, RecordMemo memo) {
        JsonNode run = firstRun(root);
        if (run == null) {
            return;
        }
        JsonNode first = run.deepCopy();
        replaceRun(root, first);
        RunRef ref = mapper.treeToValue(first, RunRef.class);
        boolean own = ref.principal().equals(memo.principal()) && ref.session().equals(memo.session());
        root.set("run", own ? root.stringNode(ref.run().value()) : first);
    }

    /** {@link #collapse} 的反向，在解开其他引用之前做。 */
    void restore(JsonNode root, RecordMemo memo) {
        if (!(root instanceof ObjectNode object) || !object.has("run")) {
            return;
        }
        JsonNode run = object.remove("run");
        if (run.isString()) {
            if (memo.principal() == null) {
                throw new IllegalArgumentException("a short run reference needs the file's session");
            }
            run = mapper.valueToTree(new RunRef(memo.principal(), memo.session(), new RunId(run.asString())));
        }
        restoreRun(object, run);
    }

    private static JsonNode firstRun(JsonNode node) {
        if (isRunRef(node)) {
            return node;
        }
        for (JsonNode child : node) {
            JsonNode found = firstRun(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean isRunRef(JsonNode node) {
        return node instanceof ObjectNode && node.size() == 3 && node.has("principal") && node.has("session") && node.has("run");
    }

    private static void replaceRun(JsonNode node, JsonNode run) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (field.getValue().equals(run)) {
                    field.setValue(object.stringNode(SAME_RUN));
                } else {
                    replaceRun(field.getValue(), run);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).equals(run)) {
                    array.set(i, array.stringNode(SAME_RUN));
                } else {
                    replaceRun(array.get(i), run);
                }
            }
        }
    }

    private static void restoreRun(JsonNode node, JsonNode run) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (field.getValue().isString() && SAME_RUN.equals(field.getValue().stringValue())) {
                    field.setValue(run.deepCopy());
                } else {
                    restoreRun(field.getValue(), run);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isString() && SAME_RUN.equals(array.get(i).stringValue())) {
                    array.set(i, run.deepCopy());
                } else {
                    restoreRun(array.get(i), run);
                }
            }
        }
    }
}
