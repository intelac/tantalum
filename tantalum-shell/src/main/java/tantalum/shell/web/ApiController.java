package tantalum.shell.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tantalum.kernel.Kernel;
import tantalum.shell.auth.LoginCheck;
import tantalum.shell.auth.UserStore;
import tantalum.shell.boot.Engine;
import tantalum.shell.boot.TantalumProperties;
import tantalum.shell.conversation.ItemJson;
import tantalum.shell.conversation.MessagePage;
import tantalum.shell.conversation.SessionSummary;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.sse.SseHub;
import tantalum.shell.sse.SseMessage;
import tantalum.shell.store.TurnQuery;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.state.Ended;
import tantalum.value.state.RunState;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** REST 接口和 SSE。只校验和转发，不等待模型结果。身份只来自登录会话，不看任何请求头。 */
@RestController
public class ApiController {

    private static final Logger LOG = LoggerFactory.getLogger(ApiController.class);

    private static final Set<String> MODES = Set.of("prompt", "steer", "follow-up");
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final Engine engine;
    private final UserStore users;
    private final TantalumProperties properties;
    private final Duration heartbeat;
    private final long claudeContextWindow;
    private final long openAiContextWindow;

    public ApiController(Engine engine, UserStore users, TantalumProperties properties,
                         @Value("${tantalum.sse-heartbeat:15s}") Duration heartbeat,
                         @Value("${tantalum.claude.context-window:200000}") long claudeContextWindow,
                         @Value("${tantalum.openai.context-window:0}") long openAiContextWindow) {
        this.engine = engine;
        this.users = users;
        this.properties = properties;
        this.heartbeat = heartbeat;
        this.claudeContextWindow = claudeContextWindow;
        this.openAiContextWindow = openAiContextWindow;
    }

    /** 当前用户的会话，最近活动的在前。 */
    @GetMapping("/api/sessions")
    ResponseEntity<String> sessions() throws SQLException {
        List<Map<String, Object>> sessions = engine.sessions(current()).stream().map(ApiController::describe).toList();
        return json(200, Map.of("sessions", sessions));
    }

    /**
     * 一页条目和排队数，默认最近一页；{@code before} 是上一页的 {@code start}。排队中的提交也是条目，{@code queued} 为 true。
     * {@code seq} 是整个会话截至的事件序号，客户端据此和 SSE 推送合并。
     */
    @GetMapping("/api/sessions/{session}/messages")
    ResponseEntity<String> messages(@PathVariable String session, @RequestParam(required = false) Integer before) {
        if (!SESSION_ID.matcher(session).matches()) {
            return error(400, "invalid session id");
        }
        if (before != null && before < 0) {
            return error(400, "invalid page position");
        }
        Optional<Engine.SessionView> found = engine.session(current(), new SessionId(session));
        if (found.isEmpty()) {
            return error(404, "no such session");
        }
        Transcript.View snapshot = found.get().transcript();
        MessagePage page = MessagePage.of(snapshot.items(), before);
        List<Map<String, Object>> items = page.items().stream()
                .map(item -> ItemJson.toMap(engine.codec().mapper(), item)).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("seq", snapshot.seq());
        response.put("items", items);
        response.put("start", page.start());
        response.put("hasMore", page.hasMore());
        response.put("followUps", found.get().state().followUps().size());
        return json(200, response);
    }

    @ExceptionHandler(Engine.SessionUnreadable.class)
    ResponseEntity<String> unreadable(Engine.SessionUnreadable failure) {
        LOG.error("session unreadable", failure);
        return error(500, "session unreadable");
    }

    /** 供应商、模型名、是否云端、上下文窗口（不知道时省略，前端就不显示占比）。Key 只在环境变量里，这里碰不到。 */
    @GetMapping("/api/config")
    ResponseEntity<String> config() {
        boolean claude = properties.provider().equals("claude");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("provider", properties.provider());
        response.put("model", claude ? properties.claude().model() : properties.openai().model());
        response.put("cloud", claude);
        long contextWindow = claude ? claudeContextWindow : openAiContextWindow;
        if (contextWindow > 0) {
            response.put("contextWindow", contextWindow);
        }
        return json(200, response);
    }

    @GetMapping("/api/runs/{run}/trace")
    ResponseEntity<String> trace(@PathVariable String run) {
        Principal user = current();
        if (engine.run(user, new RunId(run)).isEmpty()) {
            return error(404, "no such run");
        }
        return json(200, engine.turns().trace(user, new RunId(run)));
    }

    @PostMapping("/api/sessions/{session}/submissions")
    ResponseEntity<String> submit(@PathVariable String session, @RequestBody String body) {
        if (!SESSION_ID.matcher(session).matches()) {
            return error(400, "invalid session id");
        }
        JsonNode request;
        try {
            request = engine.codec().mapper().readTree(body);
        } catch (JacksonException failure) {
            return error(400, "body is not valid JSON");
        }
        String requestId = text(request, "requestId");
        String mode = text(request, "mode");
        String content = text(request, "text");
        if (blank(requestId)) {
            return error(400, "missing requestId");
        }
        if (mode == null || !MODES.contains(mode)) {
            return error(400, "mode must be one of " + MODES);
        }
        if (blank(content)) {
            return error(400, "missing text");
        }
        Engine.Submission result = engine.submit(current(), new SessionId(session),
                new RequestId(requestId), mode, content);
        if (result.status() != 200) {
            return error(result.status(), "submission rejected");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("submissionId", result.submission().value());
        response.put("duplicate", result.duplicate());
        return json(200, response);
    }

    /** 手动压缩。body 可为空或 {@code {"requestId": "...", "instructions": "..."}}；没给 requestId 时每次都是新请求。 */
    @PostMapping("/api/sessions/{session}/compact")
    ResponseEntity<String> compact(@PathVariable String session, @RequestBody(required = false) String body) {
        if (!SESSION_ID.matcher(session).matches()) {
            return error(400, "invalid session id");
        }
        JsonNode request = null;
        if (body != null && !body.isBlank()) {
            try {
                request = engine.codec().mapper().readTree(body);
            } catch (JacksonException failure) {
                return error(400, "body is not valid JSON");
            }
        }
        String requestId = request == null ? null : text(request, "requestId");
        String instructions = request == null ? null : text(request, "instructions");
        Engine.Submission result = engine.compact(current(), new SessionId(session),
                new RequestId(blank(requestId) ? UUID.randomUUID().toString() : requestId), blank(instructions) ? null : instructions);
        if (result.status() == 404) {
            return error(404, "no such session");
        }
        if (result.status() == 409) {
            return error(409, "session is running or has nothing to compact");
        }
        if (result.status() != 200) {
            return error(result.status(), "compaction rejected");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("submissionId", result.submission().value());
        response.put("duplicate", result.duplicate());
        return json(200, response);
    }

    @PostMapping("/api/runs/{run}/cancel")
    ResponseEntity<String> cancel(@PathVariable String run) {
        Principal user = current();
        Optional<RunState> found = engine.run(user, new RunId(run));
        if (found.isEmpty()) {
            return error(404, "no such run");
        }
        int status = engine.cancel(user, found.get().ref());
        return status == 200 ? json(200, Map.of("cancelled", true)) : error(status, "cancel rejected");
    }

    @GetMapping("/api/runs/{run}")
    ResponseEntity<String> run(@PathVariable String run) {
        return engine.run(current(), new RunId(run))
                .map(found -> json(200, describe(found)))
                .orElseGet(() -> error(404, "no such run"));
    }

    @GetMapping("/api/sessions/{session}/runs")
    ResponseEntity<String> runs(@PathVariable String session) {
        if (!SESSION_ID.matcher(session).matches()) {
            return error(400, "invalid session id");
        }
        Optional<Engine.SessionView> found = engine.session(current(), new SessionId(session));
        if (found.isEmpty()) {
            return error(404, "no such session");
        }
        List<Map<String, Object>> runs = found.get().runs().stream()
                .sorted(Comparator.comparingLong(run -> run.ref().run().seq()))
                .map(ApiController::describe).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("runs", runs);
        response.put("followUps", found.get().state().followUps().size());
        return json(200, response);
    }

    @GetMapping("/api/runs/{run}/turns")
    ResponseEntity<String> turns(@PathVariable String run) {
        Principal user = current();
        if (engine.run(user, new RunId(run)).isEmpty()) {
            return error(404, "no such run");
        }
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (TurnQuery.Turn turn : engine.turns().turns(user, new RunId(run))) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("number", turn.number());
            summary.put("command", turn.command().value());
            summary.put("messages", turn.prompt().messages().size());
            summary.put("outcome", turn.reply() != null ? "replied" : turn.failure() != null ? "failed" : "pending");
            summaries.add(summary);
        }
        return json(200, Map.of("turns", summaries));
    }

    @GetMapping("/api/runs/{run}/turns/{number}")
    ResponseEntity<String> turn(@PathVariable String run, @PathVariable int number) {
        Principal user = current();
        if (engine.run(user, new RunId(run)).isEmpty()) {
            return error(404, "no such run");
        }
        return engine.turns().turns(user, new RunId(run)).stream()
                .filter(turn -> turn.number() == number).findFirst()
                .map(turn -> json(200, turn))
                .orElseGet(() -> error(404, "no such turn"));
    }

    @GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId, HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        Principal user = current();
        HttpSession login = request.getSession(false);
        SseHub.Subscription subscription = engine.hub().subscribe(user, login == null ? null : login.getId(), lastEventId);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(failure -> subscription.close());
        Thread.ofVirtual().start(() -> pump(user, login, subscription, emitter));
        return emitter;
    }

    /**
     * 每个心跳周期检查一次登录是否还有效（过期、停用、改密码），不管连接忙不忙：停用和改密码可能来自另一个进程的命令行，
     * 这边收不到通知。无论怎么退出，订阅都要注销。
     */
    private void pump(Principal user, HttpSession login, SseHub.Subscription subscription, SseEmitter emitter) {
        try {
            stream(user, login, subscription, emitter);
        } catch (IOException | SQLException | IllegalStateException gone) {
            // 客户端走了，或容器已经结束这次响应。
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            emitter.completeWithError(failure);
        } finally {
            subscription.close();
        }
    }

    private void stream(Principal user, HttpSession login, SseHub.Subscription subscription, SseEmitter emitter)
            throws IOException, SQLException, InterruptedException {
        LoginCheck check = new LoginCheck(users);
        long nextCheck = System.currentTimeMillis() + heartbeat.toMillis();
        while (!subscription.closed()) {
            SseMessage message = subscription.poll(Math.max(1, nextCheck - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (subscription.closed()) {
                break;
            }
            long now = System.currentTimeMillis();
            if (now >= nextCheck) {
                if (!check.stillValid(user.value(), login, now)) {
                    subscription.close();
                    break;
                }
                nextCheck = now + heartbeat.toMillis();
                if (message == null) {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                }
            }
            if (message == null) {
                continue;
            }
            SseEmitter.SseEventBuilder event = SseEmitter.event().name(message.event()).data(message.data(), MediaType.TEXT_PLAIN);
            if (message.id() != null) {
                event.id(message.id());
            }
            emitter.send(event);
        }
        emitter.complete();
    }

    /** 在 Web 服务器开始停止之前断开 SSE，否则关闭时要等这些长连接结束。 */
    @EventListener(ContextClosedEvent.class)
    void closeEventStreams() {
        engine.hub().closeEveryone();
    }

    private static Principal current() {
        return new Principal(SecurityContextHolder.getContext().getAuthentication().getName());
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
        view.put("phase", Kernel.stateName(run));
        if (run.phase() instanceof Ended ended) {
            view.put("endReason", ended.reason().name());
            view.put("detail", ended.detail());
        }
        return view;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
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
