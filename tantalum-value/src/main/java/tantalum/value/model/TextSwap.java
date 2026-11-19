package tantalum.value.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.value.event.TextWithdrawn.Swap;
import tantalum.value.json.Json;

/**
 * 撤下文字时的换法（{@link tantalum.value.event.TextWithdrawn}）：只按字面，原文逐字出现在哪里就换哪里。纯计算，内核、会话的视图
 * 和投影（记录页、搜索）用的是同一套。{@link #places()} 数着换了几处（一个文字块里出现几次算几处）。
 * <p>换：文字、工具结果、钩子的话、插件附的内容、任务的结果、工具调用参数里的字符串值。
 * 不换：带签名的思考块（改了提供方会拒绝）、附件、技能的正文（那是技能文件的内容，不是谁说的话）。
 */
public final class TextSwap {

    private final List<Swap> swaps;
    private int places;

    /** 办不了的替换（见 {@link Swap#usable}）不算。 */
    public TextSwap(List<Swap> swaps) {
        this.swaps = swaps.stream().filter(Swap::usable).toList();
    }

    public boolean isEmpty() {
        return swaps.isEmpty();
    }

    public int places() {
        return places;
    }

    public String text(String text) {
        if (text == null) {
            return null;
        }
        String current = text;
        for (Swap swap : swaps) {
            int at = current.indexOf(swap.original());
            if (at < 0) {
                continue;
            }
            StringBuilder swapped = new StringBuilder(current.length());
            int from = 0;
            while (at >= 0) {
                swapped.append(current, from, at).append(swap.replacement());
                from = at + swap.original().length();
                at = current.indexOf(swap.original(), from);
                places++;
            }
            current = swapped.append(current, from, current.length()).toString();
        }
        return current;
    }

    public List<Message> messages(List<Message> messages) {
        List<Message> out = new ArrayList<>(messages.size());
        for (Message message : messages) {
            out.add(message(message));
        }
        return out;
    }

    public Message message(Message message) {
        int before = places;
        List<ContentBlock> content = new ArrayList<>(message.content().size());
        for (ContentBlock block : message.content()) {
            content.add(block(block));
        }
        return places == before ? message : new Message(message.role(), content, message.interrupted());
    }

    public ContentBlock block(ContentBlock block) {
        return switch (block) {
            case TextBlock text -> new TextBlock(text(text.text()));
            case ToolResultBlock result -> result(result);
            case HookTextBlock hook -> new HookTextBlock(hook.hook(), text(hook.text()));
            case AttachedBlock attached -> new AttachedBlock(attached.plugin(), text(attached.text()));
            case TaskResultBlock task -> new TaskResultBlock(task.task(), task.outcome(), text(task.text()));
            case ToolCallBlock call -> call(call);
            case SkillBlock skill -> skill;
            default -> block;
        };
    }

    public ToolResultBlock result(ToolResultBlock result) {
        return new ToolResultBlock(result.toolCallId(), text(result.text()), result.error(), result.code(), result.images());
    }

    /** 参数里的字符串值照换，键名不动；换过以后原样文字重新写一遍，两份保持一致。参数不是合法 JSON 的，直接在原样文字里换。 */
    ToolCallBlock call(ToolCallBlock call) {
        int before = places;
        if (call.arguments() == null) {
            String raw = text(call.rawArguments());
            return places == before ? call : new ToolCallBlock(call.id(), call.name(), raw, null);
        }
        Json arguments = json(call.arguments());
        return places == before ? call : new ToolCallBlock(call.id(), call.name(), write(arguments), arguments);
    }

    Json json(Json value) {
        return switch (value) {
            case Json.Str text -> new Json.Str(text(text.value()));
            case Json.Arr array -> new Json.Arr(array.items().stream().map(this::json).toList());
            case Json.Obj object -> {
                Map<String, Json> fields = new LinkedHashMap<>();
                object.fields().forEach((key, each) -> fields.put(key, json(each)));
                yield new Json.Obj(fields);
            }
            default -> value;
        };
    }

    /** 把 JSON 值写成文字。内核不带 JSON 库；这里只在换过参数以后用一次，写法紧凑、键的顺序照原样。 */
    public static String write(Json value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Json value, StringBuilder out) {
        switch (value) {
            case Json.Null nothing -> out.append("null");
            case Json.Bool bool -> out.append(bool.value());
            case Json.Num number -> out.append(number.value().toString());
            case Json.Str text -> quote(text.value(), out);
            case Json.Arr array -> {
                out.append('[');
                for (int i = 0; i < array.items().size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(array.items().get(i), out);
                }
                out.append(']');
            }
            case Json.Obj object -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, Json> field : object.fields().entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote(field.getKey(), out);
                    out.append(':');
                    write(field.getValue(), out);
                }
                out.append('}');
            }
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
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
        out.append('"');
    }
}
