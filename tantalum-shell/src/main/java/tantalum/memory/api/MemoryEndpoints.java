package tantalum.memory.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import tantalum.memory.DailyNotes;
import tantalum.memory.MemoryEntry;
import tantalum.memory.MemoryFormat;
import tantalum.memory.MemoryStore;
import tantalum.memory.MemoryStores;
import tantalum.memory.MemoryTools;
import tantalum.shell.plugin.PluginEndpoints;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;

/**
 * 记忆插件的接口（memory-plugin 需求 2.1、3.1），都在 {@code /api/plugin-api/memory/} 下，只读写本人的：
 * <ul>
 *   <li>{@code entries}：长期记忆，看、删、手工新增；校验与工具同源，别人的名字和不存在的名字都是 404。</li>
 *   <li>{@code notes}：每天的笔记，看、改、删（memory-capture 需求 3）。</li>
 *   <li>{@code capture}：“自动记下”此刻对这个人开没开（开关在插件页的参数里）。</li>
 * </ul>
 * 返回的样子和搬进插件之前的 {@code /api/memory…} 一样。
 */
public final class MemoryEndpoints implements PluginEndpoints {

    static final int MAX_TEXT_CHARS = 200_000;
    static final int DEFAULT_DAYS = 30;
    static final String NOT_FOUND = "memory/not-found";
    static final String INVALID = "request/invalid";

    private final MemoryStores stores;
    private final Predicate<Principal> capturing;
    private final boolean serverCaptures;

    /** {@code capturing}：这个人此刻会不会自动记下（服务器开着且他的参数开着）；{@code serverCaptures}：服务器开没开。 */
    public MemoryEndpoints(MemoryStores stores, Predicate<Principal> capturing, boolean serverCaptures) {
        this.stores = stores;
        this.capturing = capturing;
        this.serverCaptures = serverCaptures;
    }

    @Override
    public String plugin() {
        return MemoryTools.PLUGIN;
    }

    // 管理员只读查看时一样都看不到（Yi 2026-11-15：记忆是个人的），用接口的默认值：不可看。

    @Override
    public PluginResponse handle(PluginRequest request) {
        List<String> path = request.path();
        String method = request.method();
        if (path.isEmpty()) {
            return missing();
        }
        return switch (path.getFirst()) {
            case "entries" -> entries(request, method, path);
            case "notes" -> notes(request, method, path);
            case "capture" -> path.size() == 1 && method.equals("GET") ? capture(request.user()) : missing();
            default -> missing();
        };
    }

    // ---- 长期记忆 ----

    private PluginResponse entries(PluginRequest request, String method, List<String> path) {
        MemoryStore store = stores.of(request.user());
        if (path.size() == 1 && method.equals("GET")) {
            List<Json> entries = new ArrayList<>();
            for (MemoryEntry entry : store.list()) {
                entries.add(summary(entry));
            }
            return PluginResponse.ok(obj("entries", new Json.Arr(entries), "used", num(entries.size()), "max", num(store.limits().maxEntries())));
        }
        if (path.size() == 1 && method.equals("POST")) {
            if (!(request.body() instanceof Json.Obj input)) {
                return PluginResponse.error(400, INVALID, "body must be a JSON object", Map.of());
            }
            MemoryStore.WriteResult result = store.write(text(input, "name"), text(input, "type"), text(input, "description"),
                    text(input, "content"));
            return switch (result) {
                case MemoryStore.WriteResult.Created created -> new PluginResponse(201, full(created.entry()));
                case MemoryStore.WriteResult.Updated updated -> PluginResponse.ok(full(updated.entry()));
                case MemoryStore.WriteResult.Rejected rejected -> PluginResponse.error(400, code(rejected.problem()), rejected.reason(),
                        params(rejected.params()));
            };
        }
        if (path.size() == 2) {
            String name = path.get(1);
            if (method.equals("GET")) {
                Optional<MemoryEntry> entry = MemoryFormat.isValidName(name) ? store.read(name) : Optional.empty();
                return entry.map(found -> PluginResponse.ok(full(found))).orElseGet(MemoryEndpoints::noMemory);
            }
            if (method.equals("DELETE")) {
                return MemoryFormat.isValidName(name) && store.delete(name) ? PluginResponse.noContent() : noMemory();
            }
        }
        return missing();
    }

    static String code(MemoryStore.Problem problem) {
        return switch (problem) {
            case INVALID_NAME -> "memory/invalid-name";
            case RESERVED_NAME -> "memory/reserved-name";
            case INVALID -> "memory/invalid";
            case TOO_LONG -> "memory/too-long";
            case FULL -> "memory/full";
            case INDEX_FULL -> "memory/index-full";
            case CREDENTIAL -> "memory/credential";
        };
    }

    private static Json summary(MemoryEntry entry) {
        return new Json.Obj(summaryFields(entry));
    }

    private static Map<String, Json> summaryFields(MemoryEntry entry) {
        Map<String, Json> view = new LinkedHashMap<>();
        view.put("name", new Json.Str(entry.name()));
        view.put("type", new Json.Str(entry.type().key()));
        view.put("description", new Json.Str(entry.description()));
        view.put("updatedAt", new Json.Str(entry.updatedAt().toString()));
        return view;
    }

    private static Json full(MemoryEntry entry) {
        Map<String, Json> view = summaryFields(entry);
        view.put("content", new Json.Str(entry.content()));
        return new Json.Obj(view);
    }

    private static PluginResponse noMemory() {
        return PluginResponse.error(404, NOT_FOUND, "no such memory", Map.of());
    }

    // ---- 每天的笔记 ----

    private PluginResponse notes(PluginRequest request, String method, List<String> path) {
        DailyNotes notes = stores.notes(request.user());
        if (path.size() == 1 && method.equals("GET")) {
            int limit = days(request.query().get("days"));
            List<Json> days = new ArrayList<>();
            for (DailyNotes.Day day : notes.list(limit)) {
                List<Json> listed = new ArrayList<>();
                for (DailyNotes.Listed note : day.notes()) {
                    listed.add(obj("session", new Json.Str(note.session()), "title", new Json.Str(note.title()),
                            "sections", num(note.sections()), "preview", new Json.Str(note.preview()),
                            "updated", new Json.Str(note.updated().toString())));
                }
                days.add(obj("day", new Json.Str(day.day()), "notes", new Json.Arr(listed)));
            }
            return PluginResponse.ok(obj("days", new Json.Arr(days)));
        }
        if (path.size() != 3) {
            return missing();
        }
        String day = path.get(1);
        String session = path.get(2);
        if (!DailyNotes.validDay(day) || !DailyNotes.validSession(session)) {
            return PluginResponse.error(400, INVALID, "invalid day or session", Map.of());
        }
        return switch (method) {
            case "GET" -> notes.read(day, session).map(MemoryEndpoints::note).orElseGet(MemoryEndpoints::noNote);
            case "PUT" -> {
                String text = request.body() instanceof Json.Obj input ? text(input, "text") : null;
                if (text == null || text.length() > MAX_TEXT_CHARS) {
                    yield PluginResponse.error(400, INVALID, "text is required and at most " + MAX_TEXT_CHARS + " characters", Map.of());
                }
                yield notes.write(day, session, text) ? PluginResponse.ok(obj("saved", new Json.Bool(true))) : noNote();
            }
            case "DELETE" -> notes.delete(day, session) ? PluginResponse.ok(obj("deleted", new Json.Bool(true))) : noNote();
            default -> missing();
        };
    }

    private static int days(String raw) {
        if (raw == null) {
            return DEFAULT_DAYS;
        }
        try {
            return Math.max(1, Math.min(Integer.parseInt(raw.strip()), 366));
        } catch (NumberFormatException invalid) {
            return DEFAULT_DAYS;
        }
    }

    private static PluginResponse note(DailyNotes.Note note) {
        List<Json> sections = note.sections().stream().map(section -> {
            Map<String, Json> out = new LinkedHashMap<>();
            if (section.run() != null) {
                out.put("run", new Json.Str(section.run()));
            }
            out.put("heading", new Json.Str(section.heading()));
            out.put("body", new Json.Str(section.body()));
            return (Json) new Json.Obj(out);
        }).toList();
        return PluginResponse.ok(obj("day", new Json.Str(note.day()), "session", new Json.Str(note.session()), "title",
                new Json.Str(note.title()), "text", new Json.Str(note.text()), "sections", new Json.Arr(sections),
                "updated", new Json.Str(note.updated().toString())));
    }

    private static PluginResponse noNote() {
        return PluginResponse.error(404, INVALID, "no such note", Map.of());
    }

    // ---- 自动记下 ----

    private PluginResponse capture(Principal user) {
        return PluginResponse.ok(obj("enabled", new Json.Bool(capturing.test(user)), "server", new Json.Bool(serverCaptures)));
    }

    // ---- JSON ----

    private static PluginResponse missing() {
        return PluginResponse.error(404, NOT_FOUND, "no such memory endpoint", Map.of());
    }

    private static String text(Json.Obj input, String key) {
        return input.fields().get(key) instanceof Json.Str value ? value.value() : null;
    }

    private static Json num(long value) {
        return new Json.Num(BigDecimal.valueOf(value));
    }

    private static Map<String, Json> params(Map<String, Object> raw) {
        Map<String, Json> out = new LinkedHashMap<>();
        raw.forEach((key, value) -> out.put(key, value instanceof Number number ? new Json.Num(new BigDecimal(number.toString()))
                : new Json.Str(String.valueOf(value))));
        return out;
    }

    /** 键、值交替。 */
    private static Json.Obj obj(Object... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put((String) pairs[i], (Json) pairs[i + 1]);
        }
        return new Json.Obj(fields);
    }
}
