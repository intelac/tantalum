package tantalum.memory.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import tantalum.memory.CredentialGuard;
import tantalum.memory.DailyNotes;
import tantalum.memory.MemoryEntry;
import tantalum.memory.MemoryFormat;
import tantalum.memory.MemoryStore;
import tantalum.memory.MemoryStores;
import tantalum.memory.MemoryTools;
import tantalum.memory.PendingItems;
import tantalum.memory.WorkingMemory;
import tantalum.memory.ledger.MemoryItem;
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
            case "pending" -> path.size() == 1 && method.equals("GET") ? pending(request.user()) : missing();
            case "items" -> items(request, method, path);
            case "now" -> now(request, method, path);
            case "forget" -> path.size() == 1 && method.equals("POST") ? forget(request) : missing();
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
            // 你在控制台写的就是你说的；带 version 时比对版本（memory-final-plan 第 4 节），旧版本 409
            Integer expected = input.fields().get("version") instanceof Json.Num number ? number.value().intValue() : null;
            MemoryStore.WriteResult result = store.write(text(input, "name"), text(input, "type"), text(input, "description"),
                    text(input, "content"), MemoryItem.Actor.USER, MemoryItem.Source.USER_SAID, expected);
            return switch (result) {
                case MemoryStore.WriteResult.Created created -> new PluginResponse(201, full(created.entry(), store));
                case MemoryStore.WriteResult.Updated updated -> PluginResponse.ok(full(updated.entry(), store));
                case MemoryStore.WriteResult.Pending pending -> PluginResponse.ok(obj("pending", new Json.Str(pending.claim().id())));
                case MemoryStore.WriteResult.Rejected rejected -> PluginResponse.error(
                        rejected.problem() == MemoryStore.Problem.CONFLICT ? 409 : 400, code(rejected.problem()), rejected.reason(),
                        params(rejected.params()));
            };
        }
        if (path.size() == 2) {
            String name = path.get(1);
            if (method.equals("GET")) {
                Optional<MemoryEntry> entry = MemoryFormat.isValidName(name) ? store.read(name) : Optional.empty();
                return entry.map(found -> PluginResponse.ok(full(found, store))).orElseGet(MemoryEndpoints::noMemory);
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
            case CONFLICT -> "memory/conflict";
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

    private static Json full(MemoryEntry entry, MemoryStore store) {
        Map<String, Json> view = summaryFields(entry);
        view.put("content", new Json.Str(entry.content()));
        view.put("version", num(store.version(entry.name())));
        return new Json.Obj(view);
    }

    private static PluginResponse noMemory() {
        return PluginResponse.error(404, NOT_FOUND, "no such memory", Map.of());
    }

    // ---- 等你确认的（memory-final-plan 第 7 节） ----

    private PluginResponse pending(Principal user) {
        List<Json> items = new ArrayList<>();
        for (MemoryItem item : new PendingItems(stores.ledger(user)).list()) {
            Map<String, Json> view = new LinkedHashMap<>();
            view.put("id", new Json.Str(item.id()));
            view.put("kind", new Json.Str(item.kind().name().toLowerCase(java.util.Locale.ROOT)));
            if (item.page() != null) {
                view.put("page", new Json.Str(item.page()));
            }
            view.put("text", new Json.Str(item.text()));
            view.put("source", new Json.Str(item.source().name().toLowerCase(java.util.Locale.ROOT)));
            if (item.attr(tantalum.memory.LibrarianProposals.PROPOSAL) != null) {
                view.put("proposal", new Json.Str(item.attr(tantalum.memory.LibrarianProposals.PROPOSAL)));
            }
            if (item.attr(MemoryStore.CONFLICTS_WITH) != null) {
                view.put("conflictsWith", new Json.Str(item.attr(MemoryStore.CONFLICTS_WITH)));
            }
            if (item.provenance() != null && item.provenance().session() != null) {
                view.put("session", new Json.Str(item.provenance().session()));
            }
            view.put("created", new Json.Str(item.created().toString()));
            items.add(new Json.Obj(view));
        }
        return PluginResponse.ok(obj("items", new Json.Arr(items)));
    }

    /** {@code POST items/{id}/confirm} 和 {@code POST items/{id}/withdraw}：确认、撤销；重复做是 200（{@code already}）。 */
    private PluginResponse items(PluginRequest request, String method, List<String> path) {
        if (path.size() != 3 || !method.equals("POST") || !(path.get(2).equals("confirm") || path.get(2).equals("withdraw"))) {
            return missing();
        }
        PendingItems pending = new PendingItems(stores.ledger(request.user()));
        PendingItems.Outcome outcome = path.get(2).equals("confirm") ? pending.confirm(path.get(1)) : pending.withdraw(path.get(1));
        return switch (outcome) {
            case DONE -> PluginResponse.ok(obj("done", new Json.Bool(true)));
            case ALREADY -> PluginResponse.ok(obj("done", new Json.Bool(true), "already", new Json.Bool(true)));
            case MISSING -> PluginResponse.error(404, NOT_FOUND, "no such pending item", Map.of());
        };
    }

    // ---- 忘掉（memory-final-plan 第 10 节①“不再使用”） ----

    /**
     * {@code POST forget {"find": "…"}}：只找，返回可能说这句话的地方 {@code {places: [{id, kind, page, text, approved}]}}，不动账。
     * {@code POST forget {"places": ["…"]}}：只去掉选中的那几行，返回 {@code {removed: [原文], stale: [没动的编号]}}。
     * 哪几行算“说的是它”由调用的人定（控制台里是用户打勾），程序不替他选。
     */
    private PluginResponse forget(PluginRequest request) {
        Json.Obj input = request.body() instanceof Json.Obj given ? given : new Json.Obj(Map.of());
        String find = text(input, "find");
        if (find != null) {
            if (find.isBlank() || find.length() > 2000) {
                return PluginResponse.error(400, INVALID, "find is required and at most 2000 characters", Map.of());
            }
            List<Json> places = new java.util.ArrayList<>();
            for (tantalum.memory.Forgetting.Place place : tantalum.memory.Forgetting.candidates(stores.ledger(request.user()), find.strip())) {
                Map<String, Json> view = new java.util.LinkedHashMap<>();
                view.put("id", new Json.Str(place.id()));
                view.put("kind", new Json.Str(place.kind().name()));
                if (place.page() != null) {
                    view.put("page", new Json.Str(place.page()));
                }
                view.put("text", new Json.Str(place.text()));
                view.put("approved", new Json.Bool(place.approved()));
                places.add(new Json.Obj(view));
            }
            return PluginResponse.ok(obj("places", new Json.Arr(places)));
        }
        if (input.fields().get("places") instanceof Json.Arr chosen) {
            List<String> ids = chosen.items().stream().filter(Json.Str.class::isInstance).map(each -> ((Json.Str) each).value()).toList();
            if (ids.isEmpty() || ids.size() > 100) {
                return PluginResponse.error(400, INVALID, "places must hold 1 to 100 ids", Map.of());
            }
            tantalum.memory.Forgetting.Chosen done;
            tantalum.memory.MemoryStore store = stores.of(request.user());
            synchronized (store) {
                done = tantalum.memory.Forgetting.forget(store.ledger(), ids, MemoryItem.Actor.USER);
            }
            Map<String, Json> view = new java.util.LinkedHashMap<>();
            view.put("removed", new Json.Arr(done.removed().stream().<Json>map(Json.Str::new).toList()));
            view.put("stale", new Json.Arr(done.stale().stream().<Json>map(Json.Str::new).toList()));
            return PluginResponse.ok(new Json.Obj(view));
        }
        return PluginResponse.error(400, INVALID, "find or places is required", Map.of());
    }

    // ---- 工作记忆（memory-final-plan 第 5 节） ----

    private PluginResponse now(PluginRequest request, String method, List<String> path) {
        WorkingMemory working = stores.working(request.user());
        if (path.size() == 1 && method.equals("GET")) {
            java.time.Instant at = working.ledger().clock().instant();
            List<Json> items = new ArrayList<>();
            for (MemoryItem item : working.items()) {
                Map<String, Json> view = new LinkedHashMap<>();
                view.put("id", new Json.Str(item.id()));
                view.put("version", num(item.version()));
                view.put("kind", new Json.Str(item.kind().name().toLowerCase(java.util.Locale.ROOT)));
                view.put("title", new Json.Str(item.attr(WorkingMemory.TITLE) == null ? item.text() : item.attr(WorkingMemory.TITLE)));
                view.put("status", new Json.Str(WorkingMemory.status(item)));
                for (String key : List.of(WorkingMemory.NEXT, WorkingMemory.TO, WorkingMemory.DUE, WorkingMemory.ON, WorkingMemory.SESSION)) {
                    if (item.attr(key) != null) {
                        view.put(key, new Json.Str(item.attr(key)));
                    }
                }
                view.put("source", new Json.Str(item.source().name().toLowerCase(java.util.Locale.ROOT)));
                view.put("updated", new Json.Str(item.updated().toString()));
                view.put("stale", new Json.Bool(java.time.Duration.between(item.updated(), at).compareTo(java.time.Duration.ofDays(7)) > 0));
                items.add(new Json.Obj(view));
            }
            return PluginResponse.ok(obj("items", new Json.Arr(items)));
        }
        if (path.size() == 1 && method.equals("POST")) {
            if (!(request.body() instanceof Json.Obj input)) {
                return PluginResponse.error(400, INVALID, "body must be a JSON object", Map.of());
            }
            MemoryItem.Kind kind = switch (String.valueOf(text(input, "kind"))) {
                case "task" -> MemoryItem.Kind.TASK;
                case "commitment" -> MemoryItem.Kind.COMMITMENT;
                case "waiting" -> MemoryItem.Kind.WAITING;
                case "noticed" -> MemoryItem.Kind.NOTICED;
                default -> null;
            };
            String title = text(input, "title");
            if (kind == null || title == null || title.isBlank() || title.length() > 500) {
                return PluginResponse.error(400, INVALID, "kind (task, commitment, waiting, noticed) and title (at most 500 characters) are required",
                        Map.of());
            }
            if (CredentialGuard.detect(title).isPresent()) {
                return PluginResponse.error(400, code(MemoryStore.Problem.CREDENTIAL), CredentialGuard.REASON, Map.of());
            }
            Optional<MemoryItem> added = working.add(kind, title, details(input), MemoryItem.Source.USER_SAID, MemoryItem.Actor.USER, null,
                    null, false, null);
            return added.map(item -> new PluginResponse(201, obj("id", new Json.Str(item.id()), "version", num(item.version()))))
                    .orElseGet(() -> PluginResponse.error(409, "memory/duplicate", "the same item is already open", Map.of()));
        }
        if (path.size() == 2 && method.equals("PUT")) {
            if (!(request.body() instanceof Json.Obj input) || !(input.fields().get("version") instanceof Json.Num version)) {
                return PluginResponse.error(400, INVALID, "body must be a JSON object with version", Map.of());
            }
            Map<String, String> changes = details(input);
            String status = text(input, "status");
            if (status != null) {
                if (!WorkingMemory.STATUSES.contains(status)) {
                    return PluginResponse.error(400, INVALID, "status must be one of active, waiting, done, dropped", Map.of());
                }
                changes.put(WorkingMemory.STATUS, status);
            }
            String title = text(input, "title");
            if (title != null && CredentialGuard.detect(title).isPresent()) {
                return PluginResponse.error(400, code(MemoryStore.Problem.CREDENTIAL), CredentialGuard.REASON, Map.of());
            }
            return switch (working.update(path.get(1), version.value().intValue(), title, changes, MemoryItem.Actor.USER)) {
                case WorkingMemory.Change.Done done -> PluginResponse.ok(obj("version", num(done.item().version())));
                case WorkingMemory.Change.Stale stale -> PluginResponse.error(409, code(MemoryStore.Problem.CONFLICT),
                        "the item was changed elsewhere", Map.of("version", new Json.Num(BigDecimal.valueOf(stale.current().version()))));
                case WorkingMemory.Change.Missing missing -> PluginResponse.error(404, NOT_FOUND, "no such item", Map.of());
            };
        }
        return missing();
    }

    private static Map<String, String> details(Json.Obj input) {
        Map<String, String> attrs = new LinkedHashMap<>();
        for (String key : List.of(WorkingMemory.NEXT, WorkingMemory.TO, WorkingMemory.DUE, WorkingMemory.ON)) {
            String value = text(input, key);
            if (value != null) {
                attrs.put(key, value.strip());
            }
        }
        return attrs;
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
