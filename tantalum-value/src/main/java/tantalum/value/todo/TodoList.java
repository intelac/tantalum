package tantalum.value.todo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tantalum.value.json.Json;

/**
 * 模型的待办清单（todo-tool，照 DSH {@code packages/todo/tool-todo}）：{@code todo_write} 每次给出整张清单，替换上一张。
 * 清单不另外存，当前的就是会话里最后一次成功调用的参数；这里是解析、校验和几种写法，内核（压缩时附在摘要后）和服务器（工具）共用。
 */
public record TodoList(List<Item> items) {

    /** 工具名，也是从会话里找清单时认的名字。 */
    public static final String TOOL = "todo_write";

    public enum Status {
        PENDING, IN_PROGRESS, COMPLETED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<Status> of(String wire) {
            for (Status status : values()) {
                if (status.wire().equals(wire)) {
                    return Optional.of(status);
                }
            }
            return Optional.empty();
        }
    }

    public record Item(String content, Status status) {}

    public TodoList {
        items = List.copyOf(items);
    }

    /**
     * 校验并规范化 {@code todo_write} 的参数：{@code content} 去掉首尾空白后不能为空、不能重复，状态只能是三种之一，
     * 同时至多一项在做；每项只有这两个字段。不合格抛 {@link IllegalArgumentException}，消息原样给模型，让它自己改。
     */
    public static TodoList parse(Json arguments) {
        if (!(arguments instanceof Json.Obj object) || !(object.fields().get("todos") instanceof Json.Arr array)) {
            throw new IllegalArgumentException("Invalid todos: the argument todos must be a list of {content, status}.");
        }
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int active = 0;
        for (int i = 0; i < array.items().size(); i++) {
            int number = i + 1;
            if (!(array.items().get(i) instanceof Json.Obj item)) {
                throw new IllegalArgumentException("Invalid todos: item " + number + " must be an object {content, status}.");
            }
            for (String key : item.fields().keySet()) {
                if (!key.equals("content") && !key.equals("status")) {
                    throw new IllegalArgumentException("Invalid todos: item " + number + " has an unknown field \"" + key
                            + "\"; only content and status are allowed.");
                }
            }
            String content = item.fields().get("content") instanceof Json.Str text ? text.value().strip() : "";
            if (content.isEmpty()) {
                throw new IllegalArgumentException("Invalid todos: content must not be empty (item " + number + ").");
            }
            if (!seen.add(content)) {
                throw new IllegalArgumentException("Invalid todos: duplicate content \"" + content + "\".");
            }
            Status status = item.fields().get("status") instanceof Json.Str text ? Status.of(text.value()).orElse(null) : null;
            if (status == null) {
                throw new IllegalArgumentException("Invalid todos: status must be pending, in_progress or completed (item " + number + ").");
            }
            if (status == Status.IN_PROGRESS) {
                active++;
            }
            items.add(new Item(content, status));
        }
        if (active > 1) {
            throw new IllegalArgumentException("Invalid todos: at most one item may be in_progress (got " + active + ").");
        }
        return new TodoList(items);
    }

    /** 能解析就是一张清单，不能就是空（看会话里的旧调用时用，不抛）。 */
    public static Optional<TodoList> tryParse(Json arguments) {
        try {
            return Optional.of(parse(arguments));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    /** 成功时给模型的那一行。 */
    public String summary() {
        long done = items.stream().filter(item -> item.status() == Status.COMPLETED).count();
        String current = items.stream().filter(item -> item.status() == Status.IN_PROGRESS).map(Item::content).findFirst()
                .map(content -> "; in progress: " + content).orElse(items.isEmpty() ? "" : done == items.size() ? "; all done" : "; nothing in progress");
        return "Todo list updated: " + done + " of " + items.size() + " completed" + current + ".";
    }

    /** 压缩时附在摘要后面的写法：每项一行，{@code [x]} 完成、{@code [>]} 在做、{@code [ ]} 没开始。 */
    public String lines() {
        StringBuilder out = new StringBuilder();
        for (Item item : items) {
            out.append(switch (item.status()) {
                case COMPLETED -> "[x] ";
                case IN_PROGRESS -> "[>] ";
                case PENDING -> "[ ] ";
            }).append(item.content()).append('\n');
        }
        return out.toString().stripTrailing();
    }

    /** {@link #lines} 的反方向；认不出的行跳过。 */
    public static TodoList fromLines(String text) {
        List<Item> items = new ArrayList<>();
        for (String line : text.lines().toList()) {
            if (line.length() < 5 || line.charAt(0) != '[' || line.charAt(2) != ']' || line.charAt(3) != ' ') {
                continue;
            }
            Status status = switch (line.charAt(1)) {
                case 'x' -> Status.COMPLETED;
                case '>' -> Status.IN_PROGRESS;
                case ' ' -> Status.PENDING;
                default -> null;
            };
            String content = line.substring(4).strip();
            if (status != null && !content.isEmpty()) {
                items.add(new Item(content, status));
            }
        }
        return new TodoList(items);
    }

    /** 变回工具参数的样子。 */
    public Json toJson() {
        List<Json> list = new ArrayList<>();
        for (Item item : items) {
            Map<String, Json> fields = new LinkedHashMap<>();
            fields.put("content", new Json.Str(item.content()));
            fields.put("status", new Json.Str(item.status().wire()));
            list.add(new Json.Obj(fields));
        }
        return new Json.Obj(Map.of("todos", new Json.Arr(list)));
    }
}
