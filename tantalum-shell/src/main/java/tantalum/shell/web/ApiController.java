package tantalum.shell.web;

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
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tantalum.kernel.Kernel;
import tantalum.shell.auth.UserStore;
import tantalum.shell.boot.Engine;
import tantalum.shell.boot.TantalumProperties;
import tantalum.shell.conversation.ItemJson;
import tantalum.shell.conversation.SessionSummary;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.journal.TurnQuery;
import tantalum.shell.sse.SseHub;
import tantalum.shell.sse.SseMessage;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** REST 接口和 SSE。只校验和转发，不等待模型结果。身份只来自登录会话，不看任何请求头。 */
@RestController
public class ApiController {

    private static final Set<String> MODES = Set.of("prompt", "steer", "follow-up");
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
    ResponseEntity<String> sessions() {
        Principal user = current();
        KernelState state = engine.state();
        List<Map<String, Object>> sessions = state.sessions().values().stream()
                .filter(session -> session.principal().equals(user))
                .map(session -> SessionSummary.of(session.id(),
                        session.activeRun() == null ? null : state.runs().get(session.activeRun()),
                        engine.transcript().items(session.id())))
                .sorted(Comparator.comparing(SessionSummary::lastActivity).reversed())
                .map(ApiController::describe).toList();
        return json(200, Map.of("sessions", sessions));
    }

    /** 全部条目和排队数。排队中的提交也是条目，{@code queued} 为 true。 */
    @GetMapping("/api/sessions/{session}/messages")
    ResponseEntity<String> messages(@PathVariable String session) {
        SessionId id = new SessionId(session);
        SessionState state = engine.state().sessions().get(id);
        if (state == null || !state.principal().equals(current())) {
            return error(404, "no such session");
        }
        Transcript.Snapshot snapshot = engine.transcript().snapshot(id);
        List<Map<String, Object>> items = snapshot.items().stream()
                .map(item -> ItemJson.toMap(engine.codec().mapper(), item)).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("seq", snapshot.seq());
        response.put("items", items);
        response.put("followUps", state.followUps().size());
        return json(200, response);
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
        Principal user = current();
        SessionId id = new SessionId(session);
        SessionState state = engine.state().sessions().get(id);
        if (state == null || !state.principal().equals(user)) {
            return error(404, "no such session");
        }
        List<Map<String, Object>> runs = engine.state().runs().values().stream()
                .filter(run -> run.ref().session().equals(id))
                .sorted(Comparator.comparingLong(run -> runOrder(run.ref().run())))
                .map(ApiController::describe).toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("runs", runs);
        response.put("followUps", state.followUps().size());
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
    SseEmitter events(@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = new SseEmitter(0L);
        Principal user = current();
        SseHub.Subscription subscription = engine.hub().subscribe(user, lastEventId);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(failure -> subscription.close());
        Thread.ofVirtual().start(() -> pump(user, subscription, emitter));
        return emitter;
    }

    /** 运行 ID 由内核按事件序号生成（{@code run-<seq>}）；格式不对的排在最前，不让整个接口 500。 */
    static long runOrder(RunId run) {
        String value = run.value();
        if (!value.startsWith("run-")) {
            return -1;
        }
        try {
            return Long.parseLong(value.substring(4));
        } catch (NumberFormatException odd) {
            return -1;
        }
    }

    /** 心跳时检查用户是否被停用：停用可能来自另一个进程的命令行，这边收不到通知。无论怎么退出，订阅都要注销。 */
    private void pump(Principal user, SseHub.Subscription subscription, SseEmitter emitter) {
        try {
            stream(user, subscription, emitter);
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

    private void stream(Principal user, SseHub.Subscription subscription, SseEmitter emitter)
            throws IOException, SQLException, InterruptedException {
        while (!subscription.closed()) {
            SseMessage message = subscription.poll(heartbeat.toMillis(), TimeUnit.MILLISECONDS);
            if (message == null) {
                if (subscription.closed()) {
                    break;
                }
                if (users.isDisabled(user.value())) {
                    subscription.close();
                    break;
                }
                emitter.send(SseEmitter.event().comment("keep-alive"));
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
        view.put("pendingRecovery", summary.pendingRecovery());
        return view;
    }

    private static Map<String, Object> describe(RunState run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("run", run.ref().run().value());
        view.put("session", run.ref().session().value());
        view.put("phase", Kernel.stateName(run));
        view.put("pendingRecovery", run.pendingRecovery());
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
