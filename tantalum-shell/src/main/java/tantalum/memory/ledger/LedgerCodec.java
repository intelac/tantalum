package tantalum.memory.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import tantalum.shell.plugin.JsonText;
import tantalum.value.json.Json;

/**
 * 记忆账一行的样子：{@code {"put": {条目}}}、{@code {"tombstone": {...}}} 或 {@code {"marker": {...}}}。
 * 空值不写；枚举写小写；时间写 ISO。认不出的字段读时忽略，以后加字段不破旧账。
 */
final class LedgerCodec {

    private LedgerCodec() {}

    /** 账上的一行。 */
    sealed interface Line permits Put, Tombstone, Marker {}

    /** 一条条目的新版本。 */
    record Put(MemoryItem item) implements Line {}

    /** “不再使用”：{@code id} 的条目从此不用；{@code reason} 给人看。 */
    record Tombstone(String id, Instant at, MemoryItem.Actor actor, String reason) implements Line {}

    /** 账自己的记号，比如“从旧格式迁移完成”。 */
    record Marker(String name, Instant at) implements Line {}

    static String encode(Line line) {
        Map<String, Json> outer = new LinkedHashMap<>();
        switch (line) {
            case Put put -> outer.put("put", item(put.item()));
            case Tombstone tombstone -> {
                Map<String, Json> body = new LinkedHashMap<>();
                body.put("id", str(tombstone.id()));
                body.put("at", str(tombstone.at().toString()));
                body.put("actor", enumValue(tombstone.actor()));
                if (tombstone.reason() != null) {
                    body.put("reason", str(tombstone.reason()));
                }
                outer.put("tombstone", new Json.Obj(body));
            }
            case Marker marker -> outer.put("marker", new Json.Obj(Map.of("name", str(marker.name()), "at", str(marker.at().toString()))));
        }
        return JsonText.write(new Json.Obj(outer));
    }

    static Line decode(String text) {
        if (!(JsonText.read(text) instanceof Json.Obj outer)) {
            throw new IllegalArgumentException("a ledger line is a JSON object");
        }
        if (outer.fields().get("put") instanceof Json.Obj body) {
            return new Put(item(body));
        }
        if (outer.fields().get("tombstone") instanceof Json.Obj body) {
            return new Tombstone(text(body, "id"), Instant.parse(text(body, "at")), enumOf(MemoryItem.Actor.class, text(body, "actor")),
                    optional(body, "reason"));
        }
        if (outer.fields().get("marker") instanceof Json.Obj body) {
            return new Marker(text(body, "name"), Instant.parse(text(body, "at")));
        }
        throw new IllegalArgumentException("unknown ledger line");
    }

    private static Json item(MemoryItem item) {
        Map<String, Json> map = new LinkedHashMap<>();
        map.put("id", str(item.id()));
        map.put("version", new Json.Num(BigDecimal.valueOf(item.version())));
        map.put("state", enumValue(item.state()));
        map.put("kind", enumValue(item.kind()));
        put(map, "page", item.page());
        map.put("scope", str(item.scope()));
        map.put("source", enumValue(item.source()));
        put(map, "quote", item.quote());
        if (item.provenance() != null) {
            Map<String, Json> provenance = new LinkedHashMap<>();
            put(provenance, "session", item.provenance().session());
            put(provenance, "run", item.provenance().run());
            put(provenance, "command", item.provenance().command());
            if (item.provenance().step() != null) {
                provenance.put("step", new Json.Num(BigDecimal.valueOf(item.provenance().step())));
            }
            map.put("provenance", new Json.Obj(provenance));
        }
        map.put("actor", enumValue(item.actor()));
        map.put("validFrom", str(item.validFrom().toString()));
        put(map, "supersededBy", item.supersededBy());
        if (item.lastConfirmed() != null) {
            map.put("lastConfirmed", str(item.lastConfirmed().toString()));
        }
        map.put("confidence", enumValue(item.confidence()));
        if (!item.derivedFrom().isEmpty()) {
            map.put("derivedFrom", strings(item.derivedFrom()));
        }
        if (item.status() != null) {
            map.put("status", enumValue(item.status()));
        }
        if (item.pendingConfirm()) {
            map.put("pendingConfirm", new Json.Bool(true));
        }
        map.put("text", str(item.text()));
        if (!item.attrs().isEmpty()) {
            Map<String, Json> attrs = new LinkedHashMap<>();
            new TreeMap<>(item.attrs()).forEach((key, value) -> attrs.put(key, str(value)));
            map.put("attrs", new Json.Obj(attrs));
        }
        put(map, "owner", item.owner());
        if (!item.visibleTo().isEmpty()) {
            map.put("visibleTo", strings(item.visibleTo()));
        }
        put(map, "certifiedHash", item.certifiedHash());
        put(map, "consent", item.consent());
        map.put("created", str(item.created().toString()));
        map.put("updated", str(item.updated().toString()));
        return new Json.Obj(map);
    }

    private static MemoryItem item(Json.Obj map) {
        MemoryItem.Provenance provenance = null;
        if (map.fields().get("provenance") instanceof Json.Obj body) {
            Long step = body.fields().get("step") instanceof Json.Num number ? number.value().longValue() : null;
            provenance = new MemoryItem.Provenance(optional(body, "session"), optional(body, "run"), optional(body, "command"), step);
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        if (map.fields().get("attrs") instanceof Json.Obj body) {
            body.fields().forEach((key, value) -> {
                if (value instanceof Json.Str text) {
                    attrs.put(key, text.value());
                }
            });
        }
        String status = optional(map, "status");
        String lastConfirmed = optional(map, "lastConfirmed");
        return new MemoryItem(text(map, "id"), map.fields().get("version") instanceof Json.Num number ? number.value().intValue() : 1,
                enumOf(MemoryItem.State.class, text(map, "state")), enumOf(MemoryItem.Kind.class, text(map, "kind")), optional(map, "page"),
                text(map, "scope"), enumOf(MemoryItem.Source.class, text(map, "source")), optional(map, "quote"), provenance,
                enumOf(MemoryItem.Actor.class, text(map, "actor")), Instant.parse(text(map, "validFrom")), optional(map, "supersededBy"),
                lastConfirmed == null ? null : Instant.parse(lastConfirmed), enumOf(MemoryItem.Confidence.class, text(map, "confidence")),
                strings(map, "derivedFrom"), status == null ? null : enumOf(MemoryItem.Status.class, status),
                map.fields().get("pendingConfirm") instanceof Json.Bool flag && flag.value(), text(map, "text"), attrs,
                optional(map, "owner"), strings(map, "visibleTo"), optional(map, "certifiedHash"), optional(map, "consent"),
                Instant.parse(text(map, "created")), Instant.parse(text(map, "updated")));
    }

    private static void put(Map<String, Json> map, String key, String value) {
        if (value != null) {
            map.put(key, str(value));
        }
    }

    private static Json str(String value) {
        return new Json.Str(value);
    }

    private static Json strings(List<String> values) {
        return new Json.Arr(values.stream().map(value -> (Json) new Json.Str(value)).toList());
    }

    private static List<String> strings(Json.Obj map, String key) {
        List<String> values = new ArrayList<>();
        if (map.fields().get(key) instanceof Json.Arr array) {
            array.items().forEach(item -> {
                if (item instanceof Json.Str text) {
                    values.add(text.value());
                }
            });
        }
        return values;
    }

    private static Json enumValue(Enum<?> value) {
        return str(value.name().toLowerCase(Locale.ROOT));
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    }

    private static String text(Json.Obj map, String key) {
        if (map.fields().get(key) instanceof Json.Str value) {
            return value.value();
        }
        throw new IllegalArgumentException("missing " + key);
    }

    private static String optional(Json.Obj map, String key) {
        return map.fields().get(key) instanceof Json.Str value ? value.value() : null;
    }
}
