package tantalum.value.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.value.event.TextWithdrawn.Swap;
import tantalum.value.json.Json;

/**
 * 撤下文字时的换法（{@link tantalum.value.event.TextWithdrawn}）：只按字面，原文逐字出现在哪里就换哪里。纯计算，内核、会话的视图
 * 和投影（记录页、搜索）用的是同一套。{@code places} 数的是会换几处（一个文字块里出现几次算几处）。
 * <p>换：文字、工具结果、钩子的话、插件附的内容、任务的结果、工具调用参数里的字符串值。
 * 不换：带签名的思考块（改了提供方会拒绝）、附件、技能的正文（那是技能文件的内容，不是谁说的话）。
 */
public record TextSwap(List<Swap> swaps) {

    /** 办不了的替换（见 {@link Swap#usable}）不算。 */
    public TextSwap {
        swaps = swaps.stream().filter(Swap::usable).toList();
    }

    public boolean isEmpty() {
        return swaps.isEmpty();
    }

    public String text(String text) {
        return text(text, new int[1]);
    }

    public ContentBlock block(ContentBlock block) {
        return block(block, new int[1]);
    }

    public Message message(Message message) {
        return message(message, new int[1]);
    }

    public List<Message> messages(List<Message> messages) {
        return messages(messages, new int[1]);
    }

    public ToolResultBlock result(ToolResultBlock result) {
        return (ToolResultBlock) block(result);
    }

    /** 这段文字里会换几处。 */
    public int places(String text) {
        int[] places = new int[1];
        text(text, places);
        return places[0];
    }

    public int places(ContentBlock block) {
        int[] places = new int[1];
        block(block, places);
        return places[0];
    }

    public int places(Message message) {
        int[] places = new int[1];
        message(message, places);
        return places[0];
    }

    public int places(List<Message> messages) {
        int[] places = new int[1];
        messages(messages, places);
        return places[0];
    }

    private String text(String text, int[] places) {
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
                places[0]++;
            }
            current = swapped.append(current, from, current.length()).toString();
        }
        return current;
    }

    private List<Message> messages(List<Message> messages, int[] places) {
        List<Message> out = new ArrayList<>(messages.size());
        for (Message message : messages) {
            out.add(message(message, places));
        }
        return out;
    }

    private Message message(Message message, int[] places) {
        int before = places[0];
        List<ContentBlock> content = new ArrayList<>(message.content().size());
        for (ContentBlock block : message.content()) {
            content.add(block(block, places));
        }
        return places[0] == before ? message : new Message(message.role(), content, message.interrupted());
    }

    private ContentBlock block(ContentBlock block, int[] places) {
        int before = places[0];
        ContentBlock swapped = switch (block) {
            case TextBlock text -> new TextBlock(text(text.text(), places));
            case ToolResultBlock result -> new ToolResultBlock(result.toolCallId(), text(result.text(), places), result.error(), result.code(),
                    result.images());
            case HookTextBlock hook -> new HookTextBlock(hook.hook(), text(hook.text(), places));
            case AttachedBlock attached -> new AttachedBlock(attached.plugin(), text(attached.text(), places));
            case ReportBlock report -> new ReportBlock(report.assignment(), report.outcome(), text(report.text(), places));
            case AssignmentBlock assigned -> new AssignmentBlock(assigned.assignment(), text(assigned.text(), places));
            case ToolCallBlock call -> call(call, places);
            default -> block;
        };
        return places[0] == before ? block : swapped;
    }

    /** 参数里的字符串值照换，键名不动；换过以后原样文字重新写一遍，两份保持一致。参数不是合法 JSON 的，直接在原样文字里换。 */
    private ToolCallBlock call(ToolCallBlock call, int[] places) {
        if (call.arguments() == null) {
            return new ToolCallBlock(call.id(), call.name(), text(call.rawArguments(), places), null);
        }
        Json arguments = json(call.arguments(), places);
        return new ToolCallBlock(call.id(), call.name(), write(arguments), arguments);
    }

    private Json json(Json value, int[] places) {
        return switch (value) {
            case Json.Str text -> new Json.Str(text(text.value(), places));
            case Json.Arr array -> new Json.Arr(array.items().stream().map(each -> json(each, places)).toList());
            case Json.Obj object -> {
                Map<String, Json> fields = new LinkedHashMap<>();
                object.fields().forEach((key, each) -> fields.put(key, json(each, places)));
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
