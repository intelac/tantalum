package tantalum.shell.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.shell.memory.DailyNotes;

/**
 * 每天的笔记和“自动记下”开关（memory-capture 需求 3、4）。只读写本人的；管理员只读查看时能看笔记（读接口在 ViewAs 白名单里），
 * 写接口照规矩被拒。
 */
@RestController
public class NotesController {

    static final int MAX_TEXT_CHARS = 200_000;
    static final int DEFAULT_DAYS = 30;

    private final Engine engine;
    private final WebJson json;

    public NotesController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    private DailyNotes notes() {
        return engine.memories().notes(WebJson.currentUser());
    }

    @GetMapping("/api/memory/notes")
    ResponseEntity<String> list(@RequestParam(required = false) Integer days) {
        int limit = days == null ? DEFAULT_DAYS : Math.max(1, Math.min(days, 366));
        List<Map<String, Object>> result = notes().list(limit).stream().map(day -> Map.<String, Object>of("day", day.day(),
                "notes", day.notes().stream().map(note -> Map.<String, Object>of("session", note.session(), "title", note.title(),
                        "sections", note.sections(), "preview", note.preview(), "updated", note.updated().toString())).toList())).toList();
        return json.json(200, Map.of("days", result));
    }

    @GetMapping("/api/memory/notes/{day}/{session}")
    ResponseEntity<String> read(@PathVariable String day, @PathVariable String session) {
        if (!DailyNotes.validDay(day) || !DailyNotes.validSession(session)) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid day or session");
        }
        return notes().read(day, session).map(note -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("day", note.day());
            body.put("session", note.session());
            body.put("title", note.title());
            body.put("text", note.text());
            body.put("sections", note.sections().stream().map(section -> {
                Map<String, Object> out = new LinkedHashMap<>();
                if (section.run() != null) {
                    out.put("run", section.run());
                }
                out.put("heading", section.heading());
                out.put("body", section.body());
                return out;
            }).toList());
            body.put("updated", note.updated().toString());
            return json.json(200, body);
        }).orElseGet(this::missing);
    }

    @PutMapping("/api/memory/notes/{day}/{session}")
    ResponseEntity<String> write(@PathVariable String day, @PathVariable String session, @RequestBody String raw) {
        if (!DailyNotes.validDay(day) || !DailyNotes.validSession(session)) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid day or session");
        }
        String text = json.read(raw).map(node -> WebJson.text(node, "text")).orElse(null);
        if (text == null || text.length() > MAX_TEXT_CHARS) {
            return json.error(ErrorCode.REQUEST_INVALID, "text is required and at most " + MAX_TEXT_CHARS + " characters");
        }
        return notes().write(day, session, text) ? json.json(200, Map.of("saved", true)) : missing();
    }

    @DeleteMapping("/api/memory/notes/{day}/{session}")
    ResponseEntity<String> delete(@PathVariable String day, @PathVariable String session) {
        if (!DailyNotes.validDay(day) || !DailyNotes.validSession(session)) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid day or session");
        }
        return notes().delete(day, session) ? json.json(200, Map.of("deleted", true)) : missing();
    }

    private ResponseEntity<String> missing() {
        return json.error(404, ErrorCode.REQUEST_INVALID, "no such note", Map.of());
    }

    @GetMapping("/api/memory/capture")
    ResponseEntity<String> capture() {
        return json.json(200, Map.of("enabled", engine.userSettings().memoryCapture(WebJson.currentUser()),
                "server", engine.captureSettings().enabled()));
    }

    @PutMapping("/api/memory/capture")
    ResponseEntity<String> setCapture(@RequestBody String raw) {
        Boolean enabled = json.read(raw).map(node -> node.get("enabled")).filter(node -> node != null && node.isBoolean())
                .map(node -> node.asBoolean()).orElse(null);
        if (enabled == null) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"enabled\": true|false}");
        }
        engine.userSettings().setMemoryCapture(WebJson.currentUser(), enabled);
        return capture();
    }
}
