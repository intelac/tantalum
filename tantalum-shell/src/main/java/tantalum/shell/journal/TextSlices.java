package tantalum.shell.journal;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ContainerNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 结果行里省掉重复的部分（从 {@code RecordCodec} 拆出）：内容块在文件前面出现过就整块写成引用
 * {@code "\u0000@"+SHA-256}，长字符串写成 {@code "\u0000#"+SHA-256}，是某个旧字符串掐头去尾的写成切片
 * {@code "\u0000$"+哈希+":"+开头长度+":"+结尾长度+":"+中间}；字面以 {@code \u0000} 开头的字符串多加一个 {@code \u0000}。
 * 引用只指向更早的内容，按文件顺序读一遍就能全部解开。
 */
final class TextSlices {

    static final char MARK = '\u0000';
    private static final String REF = MARK + "#";
    private static final String BLOCK_REF = MARK + "@";
    private static final String SLICE = MARK + "$";
    /** 掐头去尾至少保留这么多字符才值得写成切片。 */
    private static final int MIN_SLICE_KEPT = 64;
    /** 内容块的类型名（{@code EventCodec} 里 {@code ContentBlock} 的 {@code kind}）。 */
    private static final Set<String> BLOCK_KINDS = Set.of("text", "reasoning", "toolCall", "toolResult", "attachment", "toolChange",
            "modelChange");
    /** 序列化后至少这么长的块才写成引用。 */
    private static final int MIN_BLOCK_BYTES = 96;

    private final JsonMapper mapper;

    TextSlices(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** 按写出的顺序走一遍：见过的内容块和长字符串换成引用，第一次见的记下；字面以标记开头的字符串转义。 */
    void shrink(JsonNode node, RecordMemo memo) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                field.setValue(shrunkChild(field.getValue(), object, memo));
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, shrunkChild(array.get(i), array, memo));
            }
        }
    }

    private JsonNode shrunkChild(JsonNode value, ContainerNode<?> parent, RecordMemo memo) {
        if (value.isString()) {
            return parent.stringNode(shrunk(value.stringValue(), memo));
        }
        if (isBlock(value)) {
            String hash = blockHash(value);
            if (hash != null && memo.knowsBlock(hash)) {
                return parent.stringNode(BLOCK_REF + hash);
            }
            if (hash != null) {
                memo.rememberBlock(hash, value);
            }
        }
        shrink(value, memo);
        return value;
    }

    private static String shrunk(String text, RecordMemo memo) {
        if (RecordMemo.isLong(text)) {
            String hash = RecordMemo.hash(text);
            if (memo.knows(hash)) {
                return REF + hash;
            }
            String slice = slice(text, memo);
            memo.remember(text);
            if (slice != null) {
                return slice;
            }
        }
        return !text.isEmpty() && text.charAt(0) == MARK ? MARK + text : text;
    }

    /** 新字符串是某个旧字符串的开头加结尾、中间换了一段时，写成切片；找不到时为 null。 */
    private static String slice(String text, RecordMemo memo) {
        String best = null;
        int bestKept = MIN_SLICE_KEPT;
        for (String hash : memo.sameStart(text)) {
            String old = memo.text(hash);
            int head = commonPrefix(old, text);
            int tail = commonSuffix(old, text, Math.min(old.length(), text.length()) - head);
            if (head + tail > bestKept) {
                bestKept = head + tail;
                best = SLICE + hash + ":" + head + ":" + tail + ":" + text.substring(head, text.length() - tail);
            }
        }
        return best != null && best.length() < text.length() ? best : null;
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        // 不在代理对中间切开
        return i > 0 && Character.isHighSurrogate(a.charAt(i - 1)) ? i - 1 : i;
    }

    private static int commonSuffix(String a, String b, int limit) {
        int i = 0;
        while (i < limit && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) {
            i++;
        }
        return i > 0 && Character.isLowSurrogate(a.charAt(a.length() - i)) ? i - 1 : i;
    }

    /** {@link #shrink} 的反向：顺序相同，所以同一行里先出现的块和长字符串也能被后面的引用。 */
    void expand(JsonNode node, RecordMemo memo) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                field.setValue(expandedChild(field.getValue(), object, memo));
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, expandedChild(array.get(i), array, memo));
            }
        }
    }

    private JsonNode expandedChild(JsonNode value, ContainerNode<?> parent, RecordMemo memo) {
        if (value.isString()) {
            String text = value.stringValue();
            if (text.length() == BLOCK_REF.length() + RecordMemo.HASH_HEX && text.startsWith(BLOCK_REF)) {
                JsonNode block = memo.block(text.substring(BLOCK_REF.length()));
                if (block == null) {
                    throw new IllegalArgumentException("reference to a block that is not earlier in the file");
                }
                return block.deepCopy();
            }
            return parent.stringNode(expanded(text, memo));
        }
        expand(value, memo);
        if (isBlock(value)) {
            String hash = blockHash(value);
            if (hash != null) {
                memo.rememberBlock(hash, value);
            }
        }
        return value;
    }

    private static String expanded(String text, RecordMemo memo) {
        if (!text.isEmpty() && text.charAt(0) == MARK) {
            if (text.length() == REF.length() + RecordMemo.HASH_HEX && text.startsWith(REF)) {
                String found = memo.text(text.substring(REF.length()));
                if (found == null) {
                    throw new IllegalArgumentException("reference to a text that is not earlier in the file");
                }
                return found;
            }
            if (text.startsWith(SLICE)) {
                String sliced = unslice(text, memo);
                memo.remember(sliced);
                return sliced;
            }
            if (text.length() < 2 || text.charAt(1) != MARK) {
                throw new IllegalArgumentException("unknown escape in a result line");
            }
            // 字面以标记开头的字符串：去掉转义用的那个标记
            text = text.substring(1);
        }
        memo.remember(text);
        return text;
    }

    private static String unslice(String text, RecordMemo memo) {
        String[] parts = text.substring(SLICE.length()).split(":", 4);
        String old = parts.length == 4 ? memo.text(parts[0]) : null;
        if (old == null) {
            throw new IllegalArgumentException("slice of a text that is not earlier in the file");
        }
        int head = Integer.parseInt(parts[1]);
        int tail = Integer.parseInt(parts[2]);
        if (head < 0 || tail < 0 || head + tail > old.length()) {
            throw new IllegalArgumentException("slice outside its text");
        }
        return old.substring(0, head) + parts[3] + old.substring(old.length() - tail);
    }

    private static boolean isBlock(JsonNode node) {
        return node instanceof ObjectNode && node.path("kind").isString() && BLOCK_KINDS.contains(node.get("kind").asString());
    }

    /** 一个块的哈希；太小不值得引用时为 null。 */
    private String blockHash(JsonNode node) {
        byte[] json = mapper.writeValueAsBytes(node);
        return json.length < MIN_BLOCK_BYTES ? null : RecordMemo.hash(new String(json, StandardCharsets.UTF_8));
    }
}
