package tantalum.shell.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.shell.memory.MemoryEntry;
import tantalum.shell.memory.MemoryFormat;
import tantalum.shell.memory.MemoryStore;
import tantalum.value.id.Principal;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** 当前登录用户的长期记忆：看、删、手工新增。校验与工具同源；别人的名字和不存在的名字都是 404。 */
@RestController
public class MemoryController {

    private final Engine engine;

    public MemoryController(Engine engine) {
        this.engine = engine;
    }

    @GetMapping("/api/memory")
    ResponseEntity<String> list() {
        MemoryStore store = store();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (MemoryEntry entry : store.list()) {
            entries.add(summary(entry));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("entries", entries);
        response.put("used", entries.size());
        response.put("max", store.limits().maxEntries());
        return json(200, response);
    }

    @GetMapping("/api/memory/{name}")
    ResponseEntity<String> get(@PathVariable String name) {
        Optional<MemoryEntry> entry = MemoryFormat.isValidName(name) ? store().read(name) : Optional.empty();
        return entry.map(found -> json(200, full(found))).orElseGet(() -> error(404, "no such memory"));
    }

    @PostMapping("/api/memory")
    ResponseEntity<String> create(@RequestBody String body) {
        JsonNode input;
        try {
            input = engine.codec().mapper().readTree(body);
        } catch (JacksonException failure) {
            return error(400, "body is not valid JSON");
        }
        MemoryStore.WriteResult result = store().write(text(input, "name"), text(input, "type"),
                text(input, "description"), text(input, "content"));
        return switch (result) {
            case MemoryStore.WriteResult.Created created -> json(201, full(created.entry()));
            case MemoryStore.WriteResult.Updated updated -> json(200, full(updated.entry()));
            case MemoryStore.WriteResult.Rejected rejected -> error(400, rejected.reason());
        };
    }

    @DeleteMapping("/api/memory/{name}")
    ResponseEntity<String> delete(@PathVariable String name) {
        boolean deleted = MemoryFormat.isValidName(name) && store().delete(name);
        return deleted ? ResponseEntity.noContent().build() : error(404, "no such memory");
    }

    private MemoryStore store() {
        return engine.memories().of(new Principal(SecurityContextHolder.getContext().getAuthentication().getName()));
    }

    private static Map<String, Object> summary(MemoryEntry entry) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", entry.name());
        view.put("type", entry.type().key());
        view.put("description", entry.description());
        view.put("updatedAt", entry.updatedAt().toString());
        return view;
    }

    private static Map<String, Object> full(MemoryEntry entry) {
        Map<String, Object> view = summary(entry);
        view.put("content", entry.content());
        return view;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }

    private ResponseEntity<String> json(int status, Object body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(engine.codec().mapper().writeValueAsString(body));
    }

    private ResponseEntity<String> error(int status, String message) {
        return json(status, Map.of("error", message));
    }
}
