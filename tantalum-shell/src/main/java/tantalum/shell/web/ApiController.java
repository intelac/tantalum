package tantalum.shell.web;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.boot.Engine;
import tantalum.shell.boot.TantalumProperties;
import tantalum.shell.conversation.ItemJson;
import tantalum.shell.conversation.MessagePage;
import tantalum.shell.conversation.SessionSummary;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.loop.Intake;
import tantalum.shell.store.SessionQueries;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.state.Ended;
import tantalum.value.state.RunState;
import tools.jackson.databind.JsonNode;

/** 会话、提交、压缩、取消和配置。只校验和转发，不等待模型结果。回放视图在 {@link RunTraceController}，推送在 {@link EventStreamController}。 */
@RestController
public class ApiController {

    private static final Logger LOG = LoggerFactory.getLogger(ApiController.class);

    private static final Set<String> MODES = Set.of("prompt", "steer", "follow-up");
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final Engine engine;
    private final TantalumProperties properties;
    private final WebJson json;

    public ApiController(Engine engine, TantalumProperties properties, WebJson json) {
        this.engine = engine;
        this.properties = properties;
        this.json = json;
    }

    /** 当前用户的会话，最近活动的在前。 */
    @GetMapping("/api/sessions")
    ResponseEntity<String> sessions() throws SQLException {
        List<Map<String, Object>> sessions = engine.queries().sessions(WebJson.currentUser()).stream().map(ApiController::describe).toList();
        return json.json(200, Map.of("sessions", sessions));
    }

    /**
     * 一页条目和排队数，默认最近一页；{@code before} 是上一页的 {@code start}。排队中的提交也是条目，{@code queued} 为 true。
     * {@code seq} 是整个会话截至的事件序号，客户端据此和 SSE 推送合并。
     */
    @GetMapping("/api/sessions/{session}/messages")
    ResponseEntity<String> messages(@PathVariable String session, @RequestParam(required = false) Integer before) {
        Optional<SessionId> id = sessionId(session);
        if (id.isEmpty()) {
            return json.error(400, "invalid session id");
        }
        if (before != null && before < 0) {
            return json.error(400, "invalid page position");
        }
        Optional<SessionQueries.SessionView> found = engine.queries().session(WebJson.currentUser(), id.get());
        if (found.isEmpty()) {
            return json.error(404, "no such session");
        }
        Transcript.View view = found.get().transcript();
        MessagePage page = MessagePage.of(view.items(), before);
        List<Map<String, Object>> items = page.items().stream()
                .map(item -> ItemJson.toMap(engine.codec().mapper(), item)).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("seq", view.seq());
        response.put("items", items);
        response.put("start", page.start());
        response.put("hasMore", page.hasMore());
        response.put("followUps", found.get().state().followUps().size());
        return json.json(200, response);
    }

    @ExceptionHandler(SessionQueries.SessionUnreadable.class)
    ResponseEntity<String> unreadable(SessionQueries.SessionUnreadable failure) {
        LOG.error("session unreadable", failure);
        return json.error(500, "session unreadable");
    }

    /** 供应商、模型名、是否云端、上下文窗口（不知道时省略，前端就不显示占比）。Key 只在环境变量里，这里碰不到。 */
    @GetMapping("/api/config")
    ResponseEntity<String> config() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("provider", properties.provider());
        response.put("model", properties.model());
        response.put("cloud", properties.provider().equals("claude"));
        if (properties.contextWindow() > 0) {
            response.put("contextWindow", properties.contextWindow());
        }
        return json.json(200, response);
    }

    @PostMapping("/api/sessions/{session}/submissions")
    ResponseEntity<String> submit(@PathVariable String session, @RequestBody String body) {
        Optional<SessionId> id = sessionId(session);
        if (id.isEmpty()) {
            return json.error(400, "invalid session id");
        }
        Optional<JsonNode> request = json.read(body);
        if (request.isEmpty()) {
            return json.error(400, "body is not valid JSON");
        }
        String requestId = WebJson.text(request.get(), "requestId");
        String mode = WebJson.text(request.get(), "mode");
        String content = WebJson.text(request.get(), "text");
        if (WebJson.blank(requestId)) {
            return json.error(400, "missing requestId");
        }
        if (mode == null || !MODES.contains(mode)) {
            return json.error(400, "mode must be one of " + MODES);
        }
        if (WebJson.blank(content)) {
            return json.error(400, "missing text");
        }
        Intake.Submission result = engine.intake().submit(WebJson.currentUser(), id.get(), new RequestId(requestId), mode, content);
        return result.status() == 200 ? accepted(result) : json.error(result.status(), "submission rejected");
    }

    /** 手动压缩。body 可为空或 {@code {"requestId": "...", "instructions": "..."}}；没给 requestId 时每次都是新请求。 */
    @PostMapping("/api/sessions/{session}/compact")
    ResponseEntity<String> compact(@PathVariable String session, @RequestBody(required = false) String body) {
        Optional<SessionId> id = sessionId(session);
        if (id.isEmpty()) {
            return json.error(400, "invalid session id");
        }
        JsonNode request = null;
        if (!WebJson.blank(body)) {
            Optional<JsonNode> parsed = json.read(body);
            if (parsed.isEmpty()) {
                return json.error(400, "body is not valid JSON");
            }
            request = parsed.get();
        }
        String requestId = request == null ? null : WebJson.text(request, "requestId");
        String instructions = request == null ? null : WebJson.text(request, "instructions");
        Intake.Submission result = engine.intake().compact(WebJson.currentUser(), id.get(),
                new RequestId(WebJson.blank(requestId) ? UUID.randomUUID().toString() : requestId),
                WebJson.blank(instructions) ? null : instructions);
        return switch (result.status()) {
            case 200 -> accepted(result);
            case 404 -> json.error(404, "no such session");
            case 409 -> json.error(409, "session is running or has nothing to compact");
            default -> json.error(result.status(), "compaction rejected");
        };
    }

    @PostMapping("/api/runs/{run}/cancel")
    ResponseEntity<String> cancel(@PathVariable String run) {
        Principal user = WebJson.currentUser();
        Optional<RunState> found = engine.queries().run(user, new RunId(run));
        if (found.isEmpty()) {
            return json.error(404, "no such run");
        }
        int status = engine.intake().cancel(user, found.get().ref());
        return status == 200 ? json.json(200, Map.of("cancelled", true)) : json.error(status, "cancel rejected");
    }

    @GetMapping("/api/runs/{run}")
    ResponseEntity<String> run(@PathVariable String run) {
        return engine.queries().run(WebJson.currentUser(), new RunId(run))
                .map(found -> json.json(200, describe(found)))
                .orElseGet(() -> json.error(404, "no such run"));
    }

    @GetMapping("/api/sessions/{session}/runs")
    ResponseEntity<String> runs(@PathVariable String session) {
        Optional<SessionId> id = sessionId(session);
        if (id.isEmpty()) {
            return json.error(400, "invalid session id");
        }
        Optional<SessionQueries.SessionView> found = engine.queries().session(WebJson.currentUser(), id.get());
        if (found.isEmpty()) {
            return json.error(404, "no such session");
        }
        List<Map<String, Object>> runs = found.get().runs().stream()
                .sorted(Comparator.comparingLong(run -> run.ref().run().seq()))
                .map(ApiController::describe).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("runs", runs);
        response.put("followUps", found.get().state().followUps().size());
        return json.json(200, response);
    }

    /** 会话 ID 只允许字母、数字、下划线和连字符：它会成为文件名的一部分。 */
    private static Optional<SessionId> sessionId(String raw) {
        return SESSION_ID.matcher(raw).matches() ? Optional.of(new SessionId(raw)) : Optional.empty();
    }

    private ResponseEntity<String> accepted(Intake.Submission result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("submissionId", result.submission().value());
        response.put("duplicate", result.duplicate());
        return json.json(200, response);
    }

    private static Map<String, Object> describe(SessionSummary summary) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", summary.id().value());
        view.put("title", summary.title());
        view.put("lastActivity", summary.lastActivity().toString());
        view.put("runStatus", summary.runStatus());
        return view;
    }

    private static Map<String, Object> describe(RunState run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("run", run.ref().run().value());
        view.put("session", run.ref().session().value());
        view.put("phase", run.phase().name());
        if (run.phase() instanceof Ended ended) {
            view.put("endReason", ended.reason().name());
            view.put("detail", ended.detail());
        }
        return view;
    }
}
